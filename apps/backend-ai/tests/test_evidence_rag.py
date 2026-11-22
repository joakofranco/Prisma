"""Tests del store de evidencias aislado por organizacion y de sus endpoints.

El caso que mas importa acá es `test_citations_never_cross_organizations`: dos organizaciones
distintas nunca deben poder ver fragmentos de evidencia una de la otra, sin importar que
evaluationId/controlId se pida.
"""

from __future__ import annotations

import asyncio
from pathlib import Path

import pytest
from fastapi.testclient import TestClient
from langchain_core.documents import Document

from app.core.config import Settings
from app.main import app
from app.rag.evidence_store import EvidenceStore, IndexSearchError


def _settings(tmp_path: Path, **overrides: object) -> Settings:
    defaults: dict[str, object] = {
        "chroma_persist_dir": str(tmp_path / "chroma"),
        "ollama_host": "http://localhost:1",
        "rag_chunk_size": 50,
        "rag_chunk_overlap": 0,
    }
    defaults.update(overrides)
    return Settings(**defaults)


class FakeEmbeddings:
    """Embeddings deterministicos por contenido, para no depender de Ollama en los tests."""

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return [self.embed_query(t) for t in texts]

    def embed_query(self, text: str) -> list[float]:
        return [float(len(text) % 7), float(hash(text) % 11), 0.0]


class FakeCitationStore:
    """Reemplaza la conexion Chroma real en tests de citations()."""

    def __init__(self, results: list[tuple[Document, float]]) -> None:
        self.results = results
        self.last_filter: dict | None = None
        self.last_k: int | None = None

    def similarity_search_with_relevance_scores(self, query, k=5, filter=None):  # noqa: A002
        self.last_filter = filter
        self.last_k = k
        return self.results[:k]


# ----------------------------------------------------------------------------------
# EvidenceStore — aislamiento entre organizaciones
# ----------------------------------------------------------------------------------


def test_collection_name_is_scoped_per_organization(tmp_path: Path) -> None:
    store = EvidenceStore(settings=_settings(tmp_path))
    assert store._collection_name("org-a") != store._collection_name("org-b")
    assert "org-a" in store._collection_name("org-a")


def test_citations_never_cross_organizations(tmp_path: Path) -> None:
    store = EvidenceStore(settings=_settings(tmp_path))
    store._stores["org-a"] = FakeCitationStore(
        [(Document(page_content="Política de org A", metadata={"file_name": "a.pdf"}), 0.9)]
    )
    store._stores["org-b"] = FakeCitationStore(
        [(Document(page_content="Política de org B", metadata={"file_name": "b.pdf"}), 0.9)]
    )

    result_a = asyncio.run(
        store.citations(
            organization_id="org-a", evaluation_id="eval-1", control_id=None, query="política"
        )
    )
    result_b = asyncio.run(
        store.citations(
            organization_id="org-b", evaluation_id="eval-1", control_id=None, query="política"
        )
    )

    assert len(result_a) == 1
    assert result_a[0]["file_name"] == "a.pdf"
    assert len(result_b) == 1
    assert result_b[0]["file_name"] == "b.pdf"
    # Pedir la misma evaluationId en ambas orgs (colisión de IDs entre tenants) no mezcla nada:
    # cada organización solo puede ver lo que está en SU propia colección.
    assert result_a[0]["file_name"] != result_b[0]["file_name"]


def test_citations_filters_by_evaluation_and_control(tmp_path: Path) -> None:
    store = EvidenceStore(settings=_settings(tmp_path))
    fake = FakeCitationStore([])
    store._stores["org-a"] = fake

    asyncio.run(
        store.citations(
            organization_id="org-a",
            evaluation_id="eval-1",
            control_id="control-9",
            query="cifrado",
        )
    )

    assert fake.last_filter == {"$and": [{"evaluation_id": "eval-1"}, {"control_id": "control-9"}]}


def test_citations_without_control_filters_only_by_evaluation(tmp_path: Path) -> None:
    store = EvidenceStore(settings=_settings(tmp_path))
    fake = FakeCitationStore([])
    store._stores["org-a"] = fake

    asyncio.run(
        store.citations(organization_id="org-a", evaluation_id="eval-1", control_id=None, query="q")
    )

    assert fake.last_filter == {"evaluation_id": "eval-1"}


def test_citations_raise_a_recoverable_error_when_the_search_fails(tmp_path: Path) -> None:
    class BoomStore:
        def similarity_search_with_relevance_scores(self, query, k=5, filter=None):  # noqa: A002
            raise RuntimeError("chroma caído")

    store = EvidenceStore(settings=_settings(tmp_path))
    store._stores["org-a"] = BoomStore()
    with pytest.raises(IndexSearchError) as err:
        asyncio.run(
            store.citations(organization_id="org-a", evaluation_id="e", control_id=None, query="q")
        )
    # Antes se tragaba el error y la API contestaba 200 con lista vacía («sin citas»).
    assert err.value.code == "INDEX_UNAVAILABLE" and not err.value.inconsistent


class _FlakyIndex:
    """Falla con «Error finding id» las primeras ``failures`` búsquedas."""

    def __init__(self, failures: int) -> None:
        self.failures = failures
        self.calls = 0

    def similarity_search_with_relevance_scores(self, query, k=5, filter=None):  # noqa: A002
        self.calls += 1
        if self.calls <= self.failures:
            raise RuntimeError("Error finding id")
        return [(Document(page_content="texto", metadata={"evidence_id": "ev-1"}), 0.9)]


def _reopen_with(store: EvidenceStore, index: _FlakyIndex) -> list[bool]:
    """Después de reabrir, la próxima conexión devuelve ``index`` (simula el índice reabierto)."""
    reopened: list[bool] = []
    real_reopen = store.reopen

    def spy() -> None:
        reopened.append(True)
        real_reopen()
        store._stores["org-a"] = index

    store.reopen = spy  # type: ignore[method-assign]
    return reopened


def test_error_finding_id_reopens_the_client_and_retries_once(tmp_path: Path) -> None:
    store = EvidenceStore(settings=_settings(tmp_path), embeddings=FakeEmbeddings())
    flaky = _FlakyIndex(failures=1)
    store._stores["org-a"] = flaky
    reopened = _reopen_with(store, flaky)

    result = asyncio.run(
        store.citations(organization_id="org-a", evaluation_id="e", control_id=None, query="q")
    )

    assert reopened == [True]
    assert [c["snippet"] for c in result] == ["texto"]
    assert flaky.calls == 2


def test_error_finding_id_that_persists_is_a_503_inconsistent_index(tmp_path: Path) -> None:
    store = EvidenceStore(settings=_settings(tmp_path), embeddings=FakeEmbeddings())
    broken = _FlakyIndex(failures=99)
    store._stores["org-a"] = broken
    _reopen_with(store, broken)

    with pytest.raises(IndexSearchError) as err:
        asyncio.run(
            store.citations(organization_id="org-a", evaluation_id="e", control_id=None, query="q")
        )

    assert err.value.code == "INDEX_INCONSISTENT" and err.value.inconsistent
    assert "reindexar" in str(err.value)
    assert broken.calls == 2, "un solo reintento, no un bucle"


def test_citations_endpoint_answers_503_retryable_instead_of_an_empty_200(
    client: TestClient,
) -> None:
    class Boom:
        async def citations(self, **kwargs):  # noqa: ANN003, ANN201
            raise IndexSearchError("índice roto", inconsistent=True)

    app.state.evidence_store = Boom()
    response = client.post(
        "/api/v1/evidence/citations",
        json={"organization_id": "o", "evaluation_id": "e", "query": "q"},
    )
    assert response.status_code == 503
    assert response.json() == {
        "code": "INDEX_INCONSISTENT",
        "detail": "índice roto",
        "retryable": True,
    }


def _store_with_fake_files(tmp_path: Path, pages: int) -> EvidenceStore:
    store = EvidenceStore(
        settings=_settings(tmp_path, rag_chunk_size=60, rag_chunk_overlap=0),
        embeddings=FakeEmbeddings(),
    )

    async def fake_load(url: str, name: str) -> list[Document]:
        return [
            Document(page_content=f"texto numero {i} de {name} " * 3, metadata={"source": name})
            for i in range(pages)
        ]

    store._download_and_load = fake_load  # type: ignore[method-assign]
    return store


def test_delete_reindex_and_search_cycles_with_a_real_chroma(tmp_path: Path) -> None:
    """Reproduce lo que pasó en el servidor (borrar por evidence_id, volver a indexar con ids
    estables y buscar, varias veces y con dos evidencias) contra un Chroma REAL (1.5.9). Acá NO
    apareció «Error finding id»: queda [POR VERIFICAR] si el defecto depende del volumen o de la
    concurrencia del servidor. Lo que se verifica es que el ciclo es consistente y no devuelve
    vacío."""
    store = _store_with_fake_files(tmp_path, pages=4)

    async def run() -> None:
        for cycle in range(12):
            for evidence_id in ("ev-a", "ev-b"):
                await store.ingest(
                    organization_id="org",
                    evaluation_id="e1",
                    evidence_id=evidence_id,
                    control_id="c1",
                    file_name=f"{evidence_id}.txt",
                    file_url="u",
                )
            if cycle % 3 == 2:
                store.delete_evidence("org", "ev-b")
            found = await store.citations(
                organization_id="org", evaluation_id="e1", control_id="c1", query="texto"
            )
            assert found, f"ciclo {cycle}: la búsqueda no puede quedar vacía"

    asyncio.run(run())


def test_all_fragments_of_a_reindexed_evidence_keep_their_ids(tmp_path: Path) -> None:
    store = _store_with_fake_files(tmp_path, pages=3)

    async def ids() -> set[str | None]:
        await store.ingest(
            organization_id="org",
            evaluation_id="e",
            evidence_id="ev-1",
            control_id=None,
            file_name="a.txt",
            file_url="u",
        )
        found = await store.citations(
            organization_id="org", evaluation_id="e", control_id=None, query="texto", top_k=20
        )
        return {c["fragment_id"] for c in found}

    first = asyncio.run(ids())
    second = asyncio.run(ids())
    assert first and first == second


def test_reopen_drops_the_cached_client_and_collections(tmp_path: Path) -> None:
    store = EvidenceStore(settings=_settings(tmp_path), embeddings=FakeEmbeddings())
    store._stores["org-a"] = object()
    before = store._chroma_client()

    store.reopen()

    assert store._stores == {} and store._client is None
    assert store._chroma_client() is not before


# ----------------------------------------------------------------------------------
# EvidenceStore — ingesta
# ----------------------------------------------------------------------------------


def test_ingest_unsupported_extension_returns_zero(tmp_path: Path) -> None:
    store = EvidenceStore(settings=_settings(tmp_path), embeddings=FakeEmbeddings())
    chunks = asyncio.run(
        store.ingest(
            organization_id="org-a",
            evaluation_id="eval-1",
            evidence_id="ev-1",
            control_id=None,
            file_name="captura.png",
            file_url="http://unused/whatever",
        )
    )
    assert chunks == 0


def test_ingest_downloads_extracts_chunks_and_indexes(tmp_path: Path, monkeypatch) -> None:
    store = EvidenceStore(settings=_settings(tmp_path), embeddings=FakeEmbeddings())

    async def fake_download(self, file_url, file_name):
        text = " ".join(["párrafo de política"] * 40)
        return [Document(page_content=text, metadata={"source": file_name})]

    monkeypatch.setattr(EvidenceStore, "_download_and_load", fake_download)

    chunks = asyncio.run(
        store.ingest(
            organization_id="org-a",
            evaluation_id="eval-1",
            evidence_id="ev-1",
            control_id="control-1",
            file_name="politica.txt",
            file_url="http://minio/presigned",
        )
    )

    assert chunks > 0
    results = asyncio.run(
        store.citations(
            organization_id="org-a", evaluation_id="eval-1", control_id=None, query="política"
        )
    )
    assert len(results) > 0
    assert results[0]["file_name"] == "politica.txt"
    assert results[0]["evidence_id"] == "ev-1"
    assert results[0]["location"].startswith("Fragmento")


def test_ingest_returns_zero_when_download_fails(tmp_path: Path, monkeypatch) -> None:
    store = EvidenceStore(settings=_settings(tmp_path), embeddings=FakeEmbeddings())

    async def fake_download(self, file_url, file_name):
        return []

    monkeypatch.setattr(EvidenceStore, "_download_and_load", fake_download)

    chunks = asyncio.run(
        store.ingest(
            organization_id="org-a",
            evaluation_id="eval-1",
            evidence_id="ev-1",
            control_id=None,
            file_name="doc.txt",
            file_url="http://minio/presigned",
        )
    )
    assert chunks == 0


def test_reingest_replaces_previous_chunks(tmp_path: Path, monkeypatch) -> None:
    store = EvidenceStore(settings=_settings(tmp_path), embeddings=FakeEmbeddings())

    async def fake_download_v1(self, file_url, file_name):
        return [Document(page_content="contenido original " * 20, metadata={})]

    async def fake_download_v2(self, file_url, file_name):
        return [Document(page_content="contenido corregido " * 20, metadata={})]

    monkeypatch.setattr(EvidenceStore, "_download_and_load", fake_download_v1)
    asyncio.run(
        store.ingest(
            organization_id="org-a",
            evaluation_id="eval-1",
            evidence_id="ev-1",
            control_id=None,
            file_name="doc.txt",
            file_url="u",
        )
    )

    monkeypatch.setattr(EvidenceStore, "_download_and_load", fake_download_v2)
    asyncio.run(
        store.ingest(
            organization_id="org-a",
            evaluation_id="eval-1",
            evidence_id="ev-1",
            control_id=None,
            file_name="doc.txt",
            file_url="u",
        )
    )

    results = asyncio.run(
        store.citations(
            organization_id="org-a",
            evaluation_id="eval-1",
            control_id=None,
            query="contenido",
            top_k=20,
        )
    )
    assert all("original" not in r["snippet"] for r in results)


# ----------------------------------------------------------------------------------
# Endpoints
# ----------------------------------------------------------------------------------


class FakeApiEvidenceStore:
    def __init__(self) -> None:
        self.ingested: list[dict] = []
        self.deleted: list[tuple[str, str]] = []

    async def ingest(self, **kwargs) -> int:
        self.ingested.append(kwargs)
        return 3

    async def citations(self, **kwargs) -> list[dict]:
        return [
            {
                "evidence_id": "ev-1",
                "file_name": "politica.pdf",
                "location": "Página 2",
                "snippet": "Se define la política de acceso...",
                "score": 0.87,
            }
        ]

    def delete_evidence(self, organization_id: str, evidence_id: str) -> None:
        self.deleted.append((organization_id, evidence_id))


@pytest.fixture
def client() -> TestClient:
    app.state.evidence_store = FakeApiEvidenceStore()
    from app.core.config import get_settings

    get_settings.cache_clear()
    return TestClient(app)


def test_ingest_endpoint(client: TestClient) -> None:
    response = client.post(
        "/api/v1/evidence/ingest",
        json={
            "organization_id": "org-a",
            "evaluation_id": "eval-1",
            "evidence_id": "ev-1",
            "control_id": "control-1",
            "file_name": "politica.pdf",
            "file_url": "http://minio/presigned",
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["indexed"] is True
    assert body["chunks"] == 3


def test_citations_endpoint(client: TestClient) -> None:
    response = client.post(
        "/api/v1/evidence/citations",
        json={
            "organization_id": "org-a",
            "evaluation_id": "eval-1",
            "control_id": "control-1",
            "query": "¿Existe una política de acceso?",
        },
    )
    assert response.status_code == 200
    citations = response.json()["citations"]
    assert len(citations) == 1
    assert citations[0]["file_name"] == "politica.pdf"
    assert citations[0]["location"] == "Página 2"


def test_delete_evidence_endpoint(client: TestClient) -> None:
    response = client.delete("/api/v1/evidence/org-a/ev-1")
    assert response.status_code == 200
    assert response.json() == {"deleted": True}


def test_ingest_rejects_blank_evidence_id(client: TestClient) -> None:
    response = client.post(
        "/api/v1/evidence/ingest",
        json={
            "organization_id": "org-a",
            "evaluation_id": "eval-1",
            "evidence_id": "",
            "control_id": None,
            "file_name": "a.pdf",
            "file_url": "http://x",
        },
    )
    assert response.status_code == 422


# ----------------------------------------------------------------------------------
# Autenticación interna (X-Internal-Api-Key)
# ----------------------------------------------------------------------------------


def test_endpoints_reject_wrong_internal_key(monkeypatch, client: TestClient) -> None:
    from app.core import config

    monkeypatch.setattr(config.get_settings(), "internal_api_key", "shh-secreto")
    response = client.post(
        "/api/v1/evidence/citations",
        json={
            "organization_id": "org-a",
            "evaluation_id": "eval-1",
            "control_id": None,
            "query": "q",
        },
        headers={"X-Internal-Api-Key": "clave-incorrecta"},
    )
    assert response.status_code == 401


def test_endpoints_accept_correct_internal_key(monkeypatch, client: TestClient) -> None:
    from app.core import config

    monkeypatch.setattr(config.get_settings(), "internal_api_key", "shh-secreto")
    response = client.post(
        "/api/v1/evidence/citations",
        json={
            "organization_id": "org-a",
            "evaluation_id": "eval-1",
            "control_id": None,
            "query": "q",
        },
        headers={"X-Internal-Api-Key": "shh-secreto"},
    )
    assert response.status_code == 200


# ----------------------------------------------------------------------------------
# C3.1 — umbral de puntaje de las citas
# ----------------------------------------------------------------------------------


def _scored(*scores: float) -> FakeCitationStore:
    return FakeCitationStore(
        [
            (Document(page_content=f"frag {i}", metadata={"evidence_id": "ev"}), s)
            for i, s in enumerate(scores)
        ]
    )


def _search(store: EvidenceStore) -> list[dict]:
    return asyncio.run(
        store.citations(organization_id="org-a", evaluation_id="e", control_id=None, query="q")
    )


def test_without_a_threshold_every_citation_is_returned(tmp_path: Path) -> None:
    store = EvidenceStore(settings=_settings(tmp_path))
    store._stores["org-a"] = _scored(0.9, 0.2, -0.4)

    assert [c["score"] for c in _search(store)] == [0.9, 0.2, -0.4]


def test_citations_below_the_threshold_are_dropped(tmp_path: Path) -> None:
    store = EvidenceStore(settings=_settings(tmp_path, ai_citation_min_score=0.5))
    store._stores["org-a"] = _scored(0.9, 0.5, 0.49, -0.4)

    assert [c["score"] for c in _search(store)] == [0.9, 0.5]


def test_a_threshold_above_every_score_returns_no_citations(tmp_path: Path) -> None:
    store = EvidenceStore(settings=_settings(tmp_path, ai_citation_min_score=0.99))
    store._stores["org-a"] = _scored(0.9, 0.8)

    assert _search(store) == []


def test_an_empty_threshold_in_the_environment_means_no_threshold(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # docker-compose pasa "${AI_CITATION_MIN_SCORE:-}" como cadena vacía.
    monkeypatch.setenv("AI_CITATION_MIN_SCORE", "")
    assert Settings(_env_file=None).ai_citation_min_score is None  # type: ignore[call-arg]
    monkeypatch.setenv("AI_CITATION_MIN_SCORE", "0.35")
    assert Settings(_env_file=None).ai_citation_min_score == 0.35  # type: ignore[call-arg]


# ----------------------------------------------------------------------------------
# D6 — id estable de fragmento
# ----------------------------------------------------------------------------------


class RecordingStore:
    """Chroma simulado que recuerda los ids con los que se agregaron los fragmentos."""

    def __init__(self) -> None:
        self.added: list[tuple[list[Document], list[str] | None]] = []

    def add_documents(self, documents, ids=None):  # noqa: ANN001
        self.added.append((documents, ids))


def _ingest_twice(tmp_path: Path, monkeypatch) -> list[list[str] | None]:  # noqa: ANN001
    store = EvidenceStore(settings=_settings(tmp_path), embeddings=FakeEmbeddings())
    recording = RecordingStore()
    store._stores["org-a"] = recording
    store.delete_evidence = lambda *a, **k: None  # type: ignore[method-assign]

    async def fake_load(self, url, name):  # noqa: ANN001
        return [Document(page_content="uno dos tres " * 40, metadata={})]

    monkeypatch.setattr(EvidenceStore, "_download_and_load", fake_load)
    for _ in range(2):
        asyncio.run(
            store.ingest(
                organization_id="org-a",
                evaluation_id="eval-1",
                evidence_id="ev-9",
                control_id="ctl-1",
                file_name="doc.txt",
                file_url="http://x/doc.txt",
            )
        )
    return [ids for _, ids in recording.added]


def test_reindexing_the_same_file_gives_the_same_fragment_ids(tmp_path: Path, monkeypatch) -> None:
    first, second = _ingest_twice(tmp_path, monkeypatch)

    assert first is not None and len(first) > 1
    assert first == second
    assert first[0] == "ev-9::0" and first[1] == "ev-9::1"
    assert len(set(first)) == len(first)


def test_citations_return_the_fragment_id(tmp_path: Path) -> None:
    store = EvidenceStore(settings=_settings(tmp_path))
    store._stores["org-a"] = FakeCitationStore(
        [(Document(id="ev-9::3", page_content="texto", metadata={"evidence_id": "ev-9"}), 0.8)]
    )

    result = asyncio.run(
        store.citations(organization_id="org-a", evaluation_id="e", control_id=None, query="q")
    )

    assert result[0]["fragment_id"] == "ev-9::3"


def test_a_citation_without_id_has_no_fragment_id(tmp_path: Path) -> None:
    store = EvidenceStore(settings=_settings(tmp_path))
    store._stores["org-a"] = _scored(0.9)

    result = asyncio.run(
        store.citations(organization_id="org-a", evaluation_id="e", control_id=None, query="q")
    )

    assert result[0]["fragment_id"] is None


def test_the_citations_endpoint_exposes_fragment_id(client: TestClient) -> None:
    from app.api.v1.evidence import EvidenceCitation

    assert (
        EvidenceCitation(
            fragment_id="ev::1",
            evidence_id="ev",
            file_name="a",
            location="l",
            snippet="s",
            score=0.5,
        ).model_dump()["fragment_id"]
        == "ev::1"
    )
