"""Tests unitarios del pipeline RAG (carga, indexado, query y respuesta)."""

import asyncio
from pathlib import Path

from langchain_core.documents import Document

from app.core.config import Settings
from app.rag.vector_store import VectorStore


def _settings(tmp_path: Path, **overrides: object) -> Settings:
    defaults: dict[str, object] = {
        "chroma_persist_dir": str(tmp_path / "chroma"),
        "rag_docs_dir": str(tmp_path / "docs"),
        "ollama_host": "http://localhost:1",
        "rag_chunk_size": 50,
        "rag_chunk_overlap": 0,
    }
    defaults.update(overrides)
    return Settings(**defaults)


class FakeRetriever:
    def __init__(self, documents: list[Document] | None = None) -> None:
        self.documents = documents or []

    def similarity_search(self, question: str, k: int = 5) -> list[Document]:
        return self.documents[:k]


class BoomRetriever:
    def similarity_search(self, question: str, k: int = 5) -> list[Document]:
        raise RuntimeError("embedding falló")


class BoomLLM:
    def invoke(self, prompt: str) -> object:
        raise RuntimeError("ollama caído")


class OkLLM:
    def invoke(self, prompt: str) -> object:
        return type("Response", (), {"content": "Respuesta generada"})()


class RecordingRetriever(FakeRetriever):
    def __init__(self) -> None:
        super().__init__()
        self.added: list[Document] = []
        self.added_ids: list[str] = []

    def add_documents(self, documents: list[Document], ids: list[str] | None = None) -> None:
        self.added.extend(documents)
        if ids:
            self.added_ids.extend(ids)


class FakeEmbeddings:
    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return [[0.1, 0.2, 0.3] for _ in texts]

    def embed_query(self, text: str) -> list[float]:
        return [0.1, 0.2, 0.3]


def test_query_returns_empty_when_not_initialized(tmp_path: Path) -> None:
    store = VectorStore(settings=_settings(tmp_path))
    assert asyncio.run(store.query("¿Qué es?")) == []


def test_query_returns_documents(tmp_path: Path) -> None:
    store = VectorStore(settings=_settings(tmp_path))
    store._vectorstore = FakeRetriever([Document(page_content="cont", metadata={"source": "a.md"})])
    result = asyncio.run(store.query("¿Qué es?"))
    assert len(result) == 1
    assert result[0].page_content == "cont"


def test_query_returns_empty_on_error(tmp_path: Path) -> None:
    store = VectorStore(settings=_settings(tmp_path))
    store._vectorstore = BoomRetriever()
    assert asyncio.run(store.query("¿Qué es?")) == []


def test_answer_falls_back_on_llm_error(tmp_path: Path) -> None:
    store = VectorStore(settings=_settings(tmp_path))
    store._vectorstore = FakeRetriever(
        [Document(page_content="fragmento", metadata={"source": "control.md"})]
    )
    store.llm = BoomLLM()
    result = asyncio.run(store.answer("¿Existe política?"))
    assert result["sources"][0]["source"] == "control.md"
    assert "No pude generar" in result["answer"]
    assert result["sources"][0]["content"] == "fragmento"


def test_answer_uses_llm_result(tmp_path: Path) -> None:
    store = VectorStore(settings=_settings(tmp_path))
    store._vectorstore = FakeRetriever([])
    store.llm = OkLLM()
    result = asyncio.run(store.answer("hola"))
    assert result["answer"] == "Respuesta generada"
    assert result["sources"] == []


class ListFormattedLLM:
    def invoke(self, prompt: str) -> object:
        content = (
            "Hay que definir e implementar una política de contraseñas formal.\n"
            "- Redactar una política corta con requisitos mínimos (longitud, rotación).\n"
            "- Configurar esos requisitos en Active Directory / el IdP.\n"
            "- Comunicarla a todo el personal y pedir acuse de recibo.\n"
        )
        return type("Response", (), {"content": content})()


def test_suggest_remediation_parses_summary_and_tips(tmp_path: Path) -> None:
    store = VectorStore(settings=_settings(tmp_path))
    store._vectorstore = FakeRetriever([Document(page_content="frag", metadata={})])
    store.llm = ListFormattedLLM()

    result = asyncio.run(
        store.suggest_remediation(
            control_code="PR.AC-1",
            control_description="La organización cuenta con una política de contraseñas",
            function_name="Proteger",
            category_name="Control de Acceso",
            subcategory_name="Gestión de identidades",
            current_level=0,
            target_level=1,
        )
    )

    assert "política de contraseñas" in result["summary"]
    assert len(result["tips"]) == 3
    assert result["tips"][0].startswith("Redactar")


def test_suggest_remediation_falls_back_on_llm_error(tmp_path: Path) -> None:
    store = VectorStore(settings=_settings(tmp_path))
    store._vectorstore = FakeRetriever([])
    store.llm = BoomLLM()

    result = asyncio.run(
        store.suggest_remediation(
            control_code="PR.AC-1",
            control_description="desc",
            function_name=None,
            category_name=None,
            subcategory_name=None,
            current_level=0,
            target_level=1,
        )
    )

    assert result["summary"] == ""
    assert len(result["tips"]) == 1
    assert "Ollama" in result["tips"][0]


def test_parse_remediation_strips_numbering_from_summary(tmp_path: Path) -> None:
    store = VectorStore(settings=_settings(tmp_path))
    text = "1. Lograr implementar una política de contraseñas.\n- Redactarla.\n- Publicarla."
    result = store._parse_remediation(text)
    assert result["summary"] == "Lograr implementar una política de contraseñas."
    assert result["tips"] == ["Redactarla.", "Publicarla."]


def test_parse_remediation_uses_full_text_when_llm_ignores_list_format(tmp_path: Path) -> None:
    store = VectorStore(settings=_settings(tmp_path))
    result = store._parse_remediation("Simplemente implementá un firewall perimetral.")
    assert result["tips"] == ["Simplemente implementá un firewall perimetral."]


def test_ingest_chunks_and_adds(tmp_path: Path) -> None:
    store = VectorStore(settings=_settings(tmp_path))
    retriever = RecordingRetriever()
    store._vectorstore = retriever
    text = " ".join(["palabra"] * 120)
    count = asyncio.run(store.ingest(text, source="manual.md"))
    assert count > 1
    assert len(retriever.added) == count
    assert retriever.added[0].metadata["source"] == "manual.md"


def test_ingest_rejects_blank_text(tmp_path: Path) -> None:
    store = VectorStore(settings=_settings(tmp_path))
    store._vectorstore = RecordingRetriever()
    assert asyncio.run(store.ingest("  ")) == 0


def test_ingest_returns_zero_on_error(tmp_path: Path) -> None:
    store = VectorStore(settings=_settings(tmp_path))

    class BoomRecorder(RecordingRetriever):
        def add_documents(self, documents: list[Document]) -> None:
            raise RuntimeError("chroma caído")

    store._vectorstore = BoomRecorder()
    assert asyncio.run(store.ingest("texto")) == 0


def test_status_not_ready_without_documents(tmp_path: Path) -> None:
    docs_dir = tmp_path / "docs"
    docs_dir.mkdir()
    store = VectorStore(settings=_settings(tmp_path))
    asyncio.run(store.initialize())
    status = store.status()
    assert status["ready"] is False
    assert status["documents"] == 0


def test_status_ready_after_initialize_with_docs(tmp_path: Path) -> None:
    docs_dir = tmp_path / "docs"
    docs_dir.mkdir()
    (docs_dir / "control.md").write_text(
        "# Control 5.1\nSe requiere una política.", encoding="utf-8"
    )
    store = VectorStore(settings=_settings(tmp_path), embeddings=FakeEmbeddings())
    asyncio.run(store.initialize())
    status = store.status()
    assert status["ready"] is True
    assert status["documents"] > 0


def test_initialize_uses_deterministic_ids_for_upsert_safety(tmp_path: Path) -> None:
    # Con más de una réplica de backend-ai contra el mismo servidor Chroma (ver
    # chroma_client.py), dos pods pueden ver la colección vacía a la vez en un arranque en frío
    # y disparar esta indexación inicial en paralelo -- los ids deben ser deterministicos
    # (mismo source + misma posición → mismo id) para que el segundo add_documents() actualice
    # los mismos fragmentos en vez de duplicarlos.
    docs_dir = tmp_path / "docs"
    docs_dir.mkdir()
    (docs_dir / "control.md").write_text("Contenido de control de prueba " * 5, encoding="utf-8")

    store = VectorStore(settings=_settings(tmp_path))
    retriever_a = RecordingRetriever()
    store._connect = lambda: retriever_a
    store._collection_count = lambda: 0
    asyncio.run(store.initialize())

    assert retriever_a.added_ids  # se generaron ids para los fragmentos
    assert len(retriever_a.added_ids) == len(retriever_a.added)
    assert retriever_a.added_ids[0].endswith("::chunk-0")

    # Simula el segundo pod indexando "en paralelo": mismos documentos de origen, otra instancia
    # de retriever -- debe producir EXACTAMENTE los mismos ids.
    store2 = VectorStore(settings=_settings(tmp_path))
    retriever_b = RecordingRetriever()
    store2._connect = lambda: retriever_b
    store2._collection_count = lambda: 0
    asyncio.run(store2.initialize())

    assert retriever_a.added_ids == retriever_b.added_ids


def test_load_documents_skips_unsupported(tmp_path: Path) -> None:
    docs_dir = tmp_path / "docs"
    docs_dir.mkdir()
    (docs_dir / "a.md").write_text("contenido markdown", encoding="utf-8")
    (docs_dir / "bin.xyz").write_bytes(b"\x00\x01")
    store = VectorStore(settings=_settings(tmp_path))
    documents = store._load_documents(docs_dir)
    assert len(documents) == 1
    assert documents[0].metadata["source"] == str(docs_dir / "a.md")
