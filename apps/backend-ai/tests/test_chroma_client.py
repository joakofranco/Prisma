"""Tests de la fábrica de cliente ChromaDB (modo local)."""

from pathlib import Path

import chromadb

from app.core.config import Settings
from app.rag.chroma_client import build_chroma_client


def _settings(tmp_path: Path, **overrides: object) -> Settings:
    defaults: dict[str, object] = {
        "chroma_persist_dir": str(tmp_path / "chroma"),
        "rag_docs_dir": str(tmp_path / "docs"),
        "ollama_host": "http://localhost:1",
    }
    defaults.update(overrides)
    return Settings(**defaults)


def test_uses_persistent_client(tmp_path: Path) -> None:
    client = build_chroma_client(_settings(tmp_path))
    assert isinstance(client, chromadb.api.client.Client)
    # PersistentClient efectivamente escribe bajo chroma_persist_dir.
    assert (tmp_path / "chroma").exists()
