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
from app.rag.evidence_store import EvidenceStore


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


def test_citations_returns_empty_on_query_error(tmp_path: Path) -> None:
    class BoomStore:
        def similarity_search_with_relevance_scores(self, query, k=5, filter=None):  # noqa: A002
            raise RuntimeError("chroma caído")

    store = EvidenceStore(settings=_settings(tmp_path))
    store._stores["org-a"] = BoomStore()
    result = asyncio.run(
        store.citations(organization_id="org-a", evaluation_id="e", control_id=None, query="q")
    )
    assert result == []


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
