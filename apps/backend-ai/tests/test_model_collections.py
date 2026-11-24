"""Cambio de modelos: colección por modelo, troceo del marco por requisito y borrado en todos los
índices. Con embeddings simulados: no necesita Ollama."""

from __future__ import annotations

import asyncio
from pathlib import Path

import pytest
from langchain_core.documents import Document

from app.core.config import Settings
from app.rag import loaders
from app.rag.chroma_client import build_chroma_client
from app.rag.collections import (
    evidence_collection,
    is_evidence_collection_of,
    mcu_collection,
    model_slug,
)
from app.rag.evidence_store import EvidenceStore
from app.rag.vector_store import VectorStore

_REPO = Path(__file__).resolve().parents[3]
_FRAMEWORK = _REPO / "docs" / "mcu-5.0" / "Marco_de_Ciberseguridad_5.0_72_requisitos.md"


def _settings(tmp_path: Path, **overrides: object) -> Settings:
    defaults: dict[str, object] = {
        "chroma_persist_dir": str(tmp_path / "chroma"),
        "rag_docs_dir": str(tmp_path / "docs"),
        "ollama_host": "http://localhost:1",
    }
    defaults.update(overrides)
    return Settings(_env_file=None, **defaults)  # type: ignore[arg-type]


class FakeEmbeddings:
    def __init__(self, dim: int = 3) -> None:
        self.dim = dim

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return [self.embed_query(t) for t in texts]

    def embed_query(self, text: str) -> list[float]:
        return [float((len(text) + i) % 7) + 0.1 for i in range(self.dim)]


# ----------------------------------------------------------------------------------
# Nombres de colección
# ----------------------------------------------------------------------------------
@pytest.mark.parametrize(
    ("model", "slug"),
    [
        ("bge-m3", "bge-m3"),
        ("bge-m3:latest", "bge-m3"),
        ("nomic-embed-text:latest", "nomic-embed-text"),
        ("BGE-M3", "bge-m3"),
        ("bge-m3:v2", "bge-m3-v2"),
        ("org/modelo:1.5", "org-modelo-1.5"),
    ],
)
def test_model_slug_gives_valid_stable_names(model: str, slug: str) -> None:
    assert model_slug(model) == slug


def test_collections_carry_the_embedding_model(tmp_path: Path) -> None:
    s = _settings(tmp_path)
    assert mcu_collection(s) == "mcu-5.0__bge-m3"
    assert evidence_collection(s, "org-1") == "evidence-org-1__bge-m3"
    other = _settings(tmp_path, ollama_embedding_model="nomic-embed-text")
    assert mcu_collection(other) == "mcu-5.0__nomic-embed-text"
    assert evidence_collection(other, "org-1") != evidence_collection(s, "org-1")


def test_is_evidence_collection_of_matches_every_model_and_the_legacy_name() -> None:
    assert is_evidence_collection_of("evidence-org-1", "org-1")  # sin sufijo (anterior)
    assert is_evidence_collection_of("evidence-org-1__bge-m3", "org-1")
    assert is_evidence_collection_of("evidence-org-1__nomic-embed-text", "org-1")
    assert not is_evidence_collection_of("evidence-org-10__bge-m3", "org-1")
    assert not is_evidence_collection_of("mcu-5.0__bge-m3", "org-1")


# ----------------------------------------------------------------------------------
# Troceo del marco por requisito (req_ctx)
# ----------------------------------------------------------------------------------
_SAMPLE = """## 2.1 Organización

### Requisito PS.1

**Adoptar una Política de Seguridad de la información**

**Objetivo** Proporcionar lineamientos.

### Requisito OR.1

**Definir roles**

Texto de OR.1.

## 2.2 Otra categoría

Texto de la categoría que NO es del requisito OR.1.
"""


def test_split_by_requirement_one_fragment_each_with_prepended_title() -> None:
    doc = Document(page_content=_SAMPLE, metadata={"source": "marco.md"})

    fragments = loaders.split_by_requirement(doc)

    assert [f.metadata["requirement"] for f in fragments] == ["PS.1", "OR.1"]
    assert fragments[0].page_content.startswith(
        "Requisito PS.1 — Adoptar una Política de Seguridad de la información\n\n"
    )
    assert "Proporcionar lineamientos" in fragments[0].page_content
    # El encabezado de la categoría siguiente no se arrastra al último requisito.
    assert "NO es del requisito" not in fragments[1].page_content
    assert fragments[1].metadata["source"] == "marco.md"


def test_a_document_without_requirements_is_left_to_the_generic_splitter() -> None:
    doc = Document(page_content="Un texto cualquiera.", metadata={"source": "otro.md"})
    assert loaders.split_by_requirement(doc) == []


@pytest.mark.skipif(not _FRAMEWORK.exists(), reason="marco MCU 5.0 no disponible")
def test_the_real_framework_gives_72_requirements_with_title_and_stable_ids(
    tmp_path: Path,
) -> None:
    docs = loaders.load_file(_FRAMEWORK)
    store = VectorStore(settings=_settings(tmp_path), embeddings=FakeEmbeddings(), llm=object())

    chunks, ids = store._chunk_framework(docs)
    chunks2, ids2 = store._chunk_framework(loaders.load_file(_FRAMEWORK))

    assert len(chunks) == 72 and len(set(ids)) == 72
    assert ids == ids2, "los ids no dependen de la corrida"
    assert all(i.endswith(tuple(f"::req-{c.metadata['requirement']}" for c in chunks)) for i in ids)
    ps1 = next(c for c in chunks if c.metadata["requirement"] == "PS.1")
    assert ps1.page_content.startswith("Requisito PS.1 — Adoptar una Política de Seguridad")
    # El tamaño de los fragmentos no cambia los ids (antes dependían de la posición).
    small = VectorStore(
        settings=_settings(tmp_path, rag_chunk_size=200), embeddings=FakeEmbeddings(), llm=object()
    )
    assert small._chunk_framework(loaders.load_file(_FRAMEWORK))[1] == ids


def test_generic_documents_keep_the_positional_chunk_ids(tmp_path: Path) -> None:
    store = VectorStore(
        settings=_settings(tmp_path, rag_chunk_size=20, rag_chunk_overlap=0),
        embeddings=FakeEmbeddings(),
        llm=object(),
    )
    doc = Document(page_content="uno dos tres cuatro cinco seis siete", metadata={"source": "g.md"})

    chunks, ids = store._chunk_framework([doc])

    assert len(chunks) == len(ids) >= 2
    assert ids[0] == "g.md::chunk-0"


# ----------------------------------------------------------------------------------
# Indexación del marco al arrancar, por modelo
# ----------------------------------------------------------------------------------
def _write_framework(tmp_path: Path) -> None:
    docs = tmp_path / "docs"
    docs.mkdir(exist_ok=True)
    (docs / "marco.md").write_text(_SAMPLE, encoding="utf-8")


def _collections(tmp_path: Path) -> dict[str, int]:
    client = build_chroma_client(_settings(tmp_path))
    return {c.name: c.count() for c in client.list_collections()}


def test_the_framework_is_indexed_in_the_collection_of_the_current_model(tmp_path: Path) -> None:
    _write_framework(tmp_path)
    store = VectorStore(settings=_settings(tmp_path), embeddings=FakeEmbeddings(), llm=object())

    asyncio.run(store.initialize())

    assert _collections(tmp_path) == {"mcu-5.0__bge-m3": 2}
    assert store.collection_name == "mcu-5.0__bge-m3"


def test_changing_the_model_indexes_a_new_collection_and_keeps_the_previous_one(
    tmp_path: Path,
) -> None:
    _write_framework(tmp_path)
    old = VectorStore(
        settings=_settings(tmp_path, ollama_embedding_model="nomic-embed-text"),
        embeddings=FakeEmbeddings(dim=3),
        llm=object(),
    )
    asyncio.run(old.initialize())

    # Otra dimensión (1024 en bge-m3): sobre la colección vieja Chroma fallaría; en la nueva no.
    new = VectorStore(settings=_settings(tmp_path), embeddings=FakeEmbeddings(dim=5), llm=object())
    asyncio.run(new.initialize())

    assert _collections(tmp_path) == {"mcu-5.0__nomic-embed-text": 2, "mcu-5.0__bge-m3": 2}


def test_an_already_indexed_collection_is_not_reindexed(tmp_path: Path) -> None:
    _write_framework(tmp_path)
    first = VectorStore(settings=_settings(tmp_path), embeddings=FakeEmbeddings(), llm=object())
    asyncio.run(first.initialize())
    (tmp_path / "docs" / "marco.md").write_text(
        _SAMPLE + "\n### Requisito XX.9\n\n**Nuevo**\n\ntexto\n", encoding="utf-8"
    )

    second = VectorStore(settings=_settings(tmp_path), embeddings=FakeEmbeddings(), llm=object())
    asyncio.run(second.initialize())

    assert _collections(tmp_path)["mcu-5.0__bge-m3"] == 2


# ----------------------------------------------------------------------------------
# Evidencia: colección por modelo y borrado en todos los índices
# ----------------------------------------------------------------------------------
def test_evidence_uses_one_collection_per_model(tmp_path: Path) -> None:
    store = EvidenceStore(settings=_settings(tmp_path), embeddings=FakeEmbeddings())
    assert store._collection_name("org-a") == "evidence-org-a__bge-m3"


def test_deleting_an_evidence_removes_it_from_every_models_index(tmp_path: Path) -> None:
    client = build_chroma_client(_settings(tmp_path))
    for name in (
        "evidence-org-a",  # índice anterior, sin sufijo
        "evidence-org-a__nomic-embed-text",
        "evidence-org-a__bge-m3",
        "evidence-org-b__bge-m3",
    ):
        col = client.get_or_create_collection(name)
        col.add(
            ids=["x1", "x2"],
            embeddings=[[0.1, 0.2], [0.3, 0.4]],
            metadatas=[{"evidence_id": "ev-1"}, {"evidence_id": "ev-2"}],
            documents=["a", "b"],
        )
    store = EvidenceStore(settings=_settings(tmp_path), embeddings=FakeEmbeddings())

    store.delete_evidence("org-a", "ev-1")

    counts = _collections(tmp_path)
    assert counts["evidence-org-a"] == 1
    assert counts["evidence-org-a__nomic-embed-text"] == 1
    assert counts["evidence-org-a__bge-m3"] == 1
    assert counts["evidence-org-b__bge-m3"] == 2, "otra organización: no se toca"
