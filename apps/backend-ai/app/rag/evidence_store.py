"""Vector store de evidencias, aislado por organizacion.

A diferencia de VectorStore (una unica coleccion Chroma con la documentacion del catalogo MCU
5.0, compartida por todos), acá cada organizacion tiene su PROPIA coleccion Chroma
(``evidence-{organization_id}``). No es un filtro por metadata que se pueda olvidar en una
query: son colecciones fisicamente distintas dentro del mismo store persistente, así que
consultar la de una organizacion no puede, estructuralmente, devolver fragmentos de otra.

Cada chunk ademas lleva ``evaluation_id``/``control_id`` en la metadata para acotar la busqueda
de citas a la evaluacion/control puntual dentro de esa organizacion.
"""

from __future__ import annotations

import logging
import os
import tempfile
from pathlib import Path
from typing import Any

import httpx
from langchain_chroma import Chroma
from langchain_core.documents import Document

from app.core.config import Settings, get_settings
from app.rag import loaders
from app.rag.chroma_client import build_chroma_client

logger = logging.getLogger(__name__)

_DOWNLOAD_TIMEOUT_SECONDS = 30.0


class IndexSearchError(Exception):
    """La búsqueda en el índice de evidencia falló (no es «sin resultados»).

    Antes estos errores se tragaban y la API contestaba 200 con una lista vacía: el auditor veía
    «sin citas» cuando en realidad el índice estaba roto o a medio reindexar. Ahora se informa
    como error recuperable (503): backend-core reintenta o usa lo ya guardado, y reindexar o
    reiniciar backend-ai lo arregla.
    """

    code = "INDEX_UNAVAILABLE"

    def __init__(self, message: str, *, inconsistent: bool = False) -> None:
        super().__init__(message)
        # «Error finding id» (Chroma): el índice de la colección quedó inconsistente.
        self.inconsistent = inconsistent
        if inconsistent:
            self.code = "INDEX_INCONSISTENT"


def _is_inconsistent_index(exc: Exception) -> bool:
    return "error finding id" in str(exc).lower()


def _search_error(exc: Exception) -> IndexSearchError:
    inconsistent = _is_inconsistent_index(exc)
    return IndexSearchError(
        "El índice de evidencia no está disponible"
        + (" (inconsistente: hay que reindexar la evidencia)" if inconsistent else "")
        + f": {type(exc).__name__}",
        inconsistent=inconsistent,
    )


class EvidenceStore:
    def __init__(self, settings: Settings | None = None, embeddings: Any | None = None) -> None:
        self.settings = settings or get_settings()
        self.embeddings = embeddings or self._default_embeddings()
        # Cache de instancias Chroma por organizacion (una coleccion por organizacion). Los
        # tests inyectan fakes acá directamente en vez de conectar a un Chroma real.
        self._stores: dict[str, Any] = {}
        # UN solo cliente Chroma para indexar, borrar y buscar: antes el borrado abría otro cliente
        # distinto del que usaba la búsqueda (3.5). Se recrea si el índice queda inconsistente.
        self._client: Any | None = None

    def _chroma_client(self) -> Any:
        if self._client is None:
            self._client = build_chroma_client(self.settings)
        return self._client

    def reopen(self) -> None:
        """Descarta el cliente y las colecciones abiertas (y la caché de Chroma por ruta): la
        próxima operación abre todo de nuevo desde lo persistido. Es lo que arreglaba reiniciar
        backend-ai cuando el índice en memoria quedaba inconsistente con el persistido."""
        self._stores.clear()
        self._client = None
        try:
            from chromadb.api.shared_system_client import SharedSystemClient

            SharedSystemClient.clear_system_cache()
        except Exception:  # pragma: no cover - depende de la versión de chromadb
            logger.warning("No se pudo limpiar la caché de sistemas de Chroma")

    def _default_embeddings(self) -> Any:
        from langchain_ollama import OllamaEmbeddings

        from app.rag.ollama_runtime import embedding_kwargs

        return OllamaEmbeddings(
            base_url=self.settings.ollama_host,
            model=self.settings.ollama_embedding_model,
            **embedding_kwargs(self.settings),
        )

    # ------------------------------------------------------------------
    # Ingesta
    # ------------------------------------------------------------------
    async def ingest(
        self,
        *,
        organization_id: str,
        evaluation_id: str,
        evidence_id: str,
        control_id: str | None,
        file_name: str,
        file_url: str,
    ) -> int:
        """Descarga la evidencia, extrae texto, trocea y embebe. Devuelve la cantidad de chunks
        indexados (0 si el formato no es soportado, la descarga/extraccion falla, o no hay
        contenido). Nunca lanza: la ingesta es siempre best-effort desde el llamador."""
        # Reemplaza cualquier indexacion previa de esta evidencia (soporta reintentos sin
        # duplicar fragmentos).
        self.delete_evidence(organization_id, evidence_id)

        documents = await self._download_and_load(file_url, file_name)
        if not documents:
            return 0

        splitter = loaders.build_splitter(
            self.settings.rag_chunk_size, self.settings.rag_chunk_overlap
        )
        chunks: list[Document] = []
        for source_doc in documents:
            chunks.extend(loaders.split_with_sections(source_doc, splitter))
        if not chunks:
            return 0

        for i, chunk in enumerate(chunks):
            page = chunk.metadata.get("page")
            section = chunk.metadata.get("section")
            # Pagina y seccion son complementarias, no excluyentes: un PDF puede tener ambas, un
            # DOCX/TXT solo seccion (no tienen paginacion real), y si no se detecto ninguna de
            # las dos cae al numero de fragmento de siempre para que la cita nunca quede vacia.
            location_parts = []
            if page is not None:
                location_parts.append(f"Página {int(page) + 1}")
            if section:
                location_parts.append(section)
            location = " · ".join(location_parts) if location_parts else f"Fragmento {i + 1}"
            chunk.metadata = {
                "evidence_id": evidence_id,
                "evaluation_id": evaluation_id,
                "control_id": control_id or "",
                "file_name": file_name,
                "location": location,
            }

        # IDs deterministicos (evidencia + posicion del fragmento): el mismo archivo reindexado
        # da los mismos IDs, asi las decisiones del auditor sobre un fragmento sobreviven a
        # re-indexar (D6, C2). Sin esto Chroma generaba UUID aleatorios en cada indexacion.
        ids = [f"{evidence_id}::{i}" for i in range(len(chunks))]
        for attempt in (1, 2):
            try:
                self._connect(organization_id).add_documents(chunks, ids=ids)
                return len(chunks)
            except Exception as exc:
                logger.exception(
                    "Falla indexando evidencia %s (org %s)", evidence_id, organization_id
                )
                if attempt == 1 and _is_inconsistent_index(exc):
                    # Indice inconsistente: se reabre el cliente y se reintenta UNA vez.
                    self.reopen()
                    continue
                return 0
        return 0

    async def _download_and_load(self, file_url: str, file_name: str) -> list[Document]:
        suffix = Path(file_name).suffix.lower()
        if suffix not in loaders.SUPPORTED_EXTENSIONS:
            logger.info("Formato de evidencia no soportado para RAG: %s", file_name)
            return []
        try:
            async with httpx.AsyncClient(timeout=_DOWNLOAD_TIMEOUT_SECONDS) as client:
                response = await client.get(file_url)
                response.raise_for_status()
                content = response.content
        except Exception:
            logger.exception("No se pudo descargar la evidencia %s", file_name)
            return []

        # mkstemp (no mktemp): crea y abre el archivo atomicamente, sin la ventana de
        # carrera entre "elegir un nombre libre" y "crearlo" que mktemp tiene.
        fd, tmp_name = tempfile.mkstemp(suffix=suffix)
        tmp_path = Path(tmp_name)
        try:
            with os.fdopen(fd, "wb") as tmp_file:
                tmp_file.write(content)
            return loaders.load_file(tmp_path)
        finally:
            tmp_path.unlink(missing_ok=True)

    # ------------------------------------------------------------------
    # Consulta
    # ------------------------------------------------------------------
    async def citations(
        self,
        *,
        organization_id: str,
        evaluation_id: str,
        control_id: str | None,
        query: str,
        top_k: int | None = None,
    ) -> list[dict[str, Any]]:
        """Busca los fragmentos mas relevantes de evidencia DE ESTA ORGANIZACION unicamente,
        acotado a la evaluacion (y control, si se especifica)."""
        where: dict[str, Any] = (
            {"$and": [{"evaluation_id": evaluation_id}, {"control_id": control_id}]}
            if control_id
            else {"evaluation_id": evaluation_id}
        )
        results = self._search_with_recovery(
            organization_id,
            lambda store: store.similarity_search_with_relevance_scores(
                query, k=top_k or self.settings.rag_top_k, filter=where
            ),
        )

        minimum = self.settings.ai_citation_min_score
        return [
            {
                "fragment_id": getattr(doc, "id", None),
                "evidence_id": doc.metadata.get("evidence_id"),
                "file_name": doc.metadata.get("file_name"),
                "location": doc.metadata.get("location"),
                "snippet": doc.page_content,
                "score": score,
            }
            for doc, score in results
            # Umbral (C3.1): lo que puntúa por debajo no es una cita que valga la pena mostrar.
            if minimum is None or score >= minimum
        ]

    def _search_with_recovery(self, organization_id: str, search: Any) -> Any:
        """Ejecuta ``search(store)``. Ante «Error finding id» reabre el cliente y reintenta UNA
        vez; si sigue fallando (o es otro error) lanza ``IndexSearchError`` (503, recuperable): no
        se devuelve una lista vacía como si no hubiera resultados."""
        try:
            return search(self._connect(organization_id))
        except Exception as exc:
            if not _is_inconsistent_index(exc):
                logger.exception(
                    "Falla la búsqueda en el índice de evidencia de %s", organization_id
                )
                raise _search_error(exc) from exc
            logger.warning(
                "Índice de evidencia inconsistente (org %s): se reabre el cliente y se reintenta",
                organization_id,
            )
        self.reopen()
        try:
            return search(self._connect(organization_id))
        except Exception as exc:
            logger.exception(
                "El índice de evidencia sigue fallando tras reabrirlo (org %s)", organization_id
            )
            raise _search_error(exc) from exc

    # ------------------------------------------------------------------
    # Mantenimiento
    # ------------------------------------------------------------------
    def delete_evidence(self, organization_id: str, evidence_id: str) -> None:
        """Borra todos los chunks de una evidencia puntual (idempotente)."""
        try:
            # Con el MISMO cliente que busca e indexa (ver ``_chroma_client``).
            collection = self._chroma_client().get_or_create_collection(
                self._collection_name(organization_id)
            )
            collection.delete(where={"evidence_id": evidence_id})
        except Exception:
            logger.exception(
                "No se pudieron borrar chunks previos de evidencia %s (org %s)",
                evidence_id,
                organization_id,
            )

    # ------------------------------------------------------------------
    # Internos
    # ------------------------------------------------------------------
    def _collection_name(self, organization_id: str) -> str:
        return f"evidence-{organization_id}"

    def _connect(self, organization_id: str) -> Any:
        # Ver app/rag/chroma_client.py: mismo criterio que VectorStore (PersistentClient local).
        if organization_id not in self._stores:
            self._stores[organization_id] = Chroma(
                client=self._chroma_client(),
                collection_name=self._collection_name(organization_id),
                embedding_function=self.embeddings,
            )
        return self._stores[organization_id]
