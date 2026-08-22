"""Fábrica del cliente ChromaDB subyacente, compartida por VectorStore y EvidenceStore.

Con una sola réplica de backend-ai, un `PersistentClient` (SQLite + archivos locales bajo
``chroma_persist_dir``) alcanza. Pero eso ata el índice al filesystem efímero de UN pod: no se
puede correr backend-ai con más de una réplica sin arriesgar corrupción (dos procesos escribiendo
el mismo SQLite) o, directamente, sin poder programar el segundo pod (un `PersistentVolumeClaim`
ReadWriteOnce sólo lo puede montar un nodo a la vez).

Por eso, en Kubernetes ChromaDB corre como su propio servicio de una sola réplica
(``chroma-server``, ver infra/kubernetes/base/chroma.yaml) con su propio PVC, y backend-ai se
conecta por HTTP -- ahí sí puede tener 2+ réplicas sin estado propio. En docker-compose y en los
tests, sin `CHROMA_SERVER_HOST` seteado, se sigue usando el `PersistentClient` de siempre (no
tiene sentido separar un servicio aparte para levantar un solo backend-ai en un laptop).
"""

from __future__ import annotations

import chromadb
from chromadb.config import Settings as ChromaSettings

from app.core.config import Settings


def build_chroma_client(settings: Settings) -> chromadb.ClientAPI:
    if settings.chroma_server_host:
        return chromadb.HttpClient(
            host=settings.chroma_server_host,
            port=settings.chroma_server_port,
            settings=_chroma_settings(),
        )
    return chromadb.PersistentClient(path=settings.chroma_persist_dir, settings=_chroma_settings())


def _chroma_settings() -> ChromaSettings:
    return ChromaSettings(anonymized_telemetry=False)
