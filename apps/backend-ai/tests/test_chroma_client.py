"""Tests de la fábrica de cliente ChromaDB (local vs servidor HTTP)."""

from pathlib import Path
from unittest.mock import patch

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


def test_uses_persistent_client_by_default(tmp_path: Path) -> None:
    client = build_chroma_client(_settings(tmp_path))
    assert isinstance(client, chromadb.api.client.Client)
    # PersistentClient efectivamente escribe bajo chroma_persist_dir.
    assert (tmp_path / "chroma").exists()


def test_uses_http_client_when_server_host_set(tmp_path: Path) -> None:
    with patch("app.rag.chroma_client.chromadb.HttpClient") as mock_http_client:
        build_chroma_client(
            _settings(tmp_path, chroma_server_host="chroma-server", chroma_server_port=9000)
        )
        mock_http_client.assert_called_once()
        _, kwargs = mock_http_client.call_args
        assert kwargs["host"] == "chroma-server"
        assert kwargs["port"] == 9000
