"""Fábrica del cliente ChromaDB subyacente, compartida por VectorStore y EvidenceStore.

Backend-ai corre con una sola réplica, así que alcanza un `PersistentClient` (SQLite + archivos
locales bajo ``chroma_persist_dir``). Escalar a más de una réplica requeriría un servidor ChromaDB
aparte (ver docs/mcu-5.0/Kubernetes-Analisis-Viabilidad.md).
"""

from __future__ import annotations

import chromadb
from chromadb.config import Settings as ChromaSettings

from app.core.config import Settings


def build_chroma_client(settings: Settings) -> chromadb.ClientAPI:
    return chromadb.PersistentClient(path=settings.chroma_persist_dir, settings=_chroma_settings())


def _chroma_settings() -> ChromaSettings:
    return ChromaSettings(anonymized_telemetry=False)
