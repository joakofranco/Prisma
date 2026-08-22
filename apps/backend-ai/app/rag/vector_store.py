"""Pipeline RAG sobre la documentación del MCU 5.0.

``Documento → Loader → Splitter → Embeddings → ChromaDB``
``Pregunta → Embedding → Retriever (top-k) → LLM (Ollama) → Respuesta + citas``

El servicio arranca aunque Ollama o el directorio de documentos no estén
disponibles: ante cualquier fallo se registra el error y las consultas
devuelven contexto vacío en lugar de tumbar el proceso.
"""

from __future__ import annotations

import logging
import re
from pathlib import Path
from typing import Any

from langchain_chroma import Chroma
from langchain_core.documents import Document
from langchain_ollama import ChatOllama, OllamaEmbeddings
from langchain_text_splitters import RecursiveCharacterTextSplitter

from app.core.config import Settings, get_settings
from app.rag import loaders
from app.rag.chroma_client import build_chroma_client

logger = logging.getLogger(__name__)

SUPPORTED_EXTENSIONS = loaders.SUPPORTED_EXTENSIONS


class VectorStore:
    """Vector store RAG sobre ChromaDB con embeddings y LLM vía Ollama."""

    def __init__(
        self,
        settings: Settings | None = None,
        embeddings: Any | None = None,
        llm: Any | None = None,
    ) -> None:
        self.settings = settings or get_settings()
        self.embeddings = embeddings or OllamaEmbeddings(
            base_url=self.settings.ollama_host,
            model=self.settings.ollama_embedding_model,
        )
        self.llm = llm or ChatOllama(
            base_url=self.settings.ollama_host,
            model=self.settings.ollama_model,
        )
        self.collection_name = "mcu-5.0"
        self._vectorstore: Chroma | None = None

    # ------------------------------------------------------------------
    # Ciclo de vida
    # ------------------------------------------------------------------
    async def initialize(self) -> None:
        """Conecta a la colección y la indexa si aún no tiene fragmentos."""
        try:
            self._vectorstore = self._connect()
            count = self._collection_count()
            if count > 0:
                logger.info(
                    "Colección %s ya indexada con %d fragmentos", self.collection_name, count
                )
                return
            docs_dir = Path(self.settings.rag_docs_dir)
            documents = self._load_documents(docs_dir)
            if not documents:
                logger.warning(
                    "Directorio %s sin documentos para indexar; la colección queda vacía",
                    self.settings.rag_docs_dir,
                )
                return
            chunks = self._split().split_documents(documents)
            # IDs deterministicos (source + posicion), no los UUID random que Chroma generaria
            # por default: con backend-ai corriendo con mas de una replica contra el mismo
            # servidor Chroma (ver chroma_client.py), dos pods pueden ver la coleccion vacia a la
            # vez en un arranque en frio y disparar esta indexacion inicial en paralelo -- con
            # IDs deterministicos, el segundo add_documents() actualiza (upsert) los mismos
            # fragmentos en vez de duplicarlos.
            ids = [
                f"{doc.metadata.get('source', 'doc')}::chunk-{i}" for i, doc in enumerate(chunks)
            ]
            self._vectorstore.add_documents(chunks, ids=ids)
            logger.info(
                "Indexados %d fragmentos de %d documentos en %s",
                len(chunks),
                len(documents),
                self.collection_name,
            )
        except Exception:
            logger.exception(
                "No se pudo inicializar el VectorStore; el servicio arranca sin índice"
            )
            self._vectorstore = None

    async def close(self) -> None:
        """ChromaDB persiste en disco; no hay recursos asíncronos que liberar."""
        logger.info("VectorStore cerrado (colección %s)", self.collection_name)

    # ------------------------------------------------------------------
    # Operaciones
    # ------------------------------------------------------------------
    async def query(self, question: str, k: int | None = None) -> list[Document]:
        """Recupera los k fragmentos más relevantes (top-k)."""
        if self._vectorstore is None:
            logger.warning("VectorStore no inicializado; query devuelve vacío")
            return []
        try:
            return self._vectorstore.similarity_search(question, k=k or self.settings.rag_top_k)
        except Exception:
            logger.exception("Falla la búsqueda por similitud")
            return []

    async def answer(self, question: str, k: int | None = None) -> dict[str, Any]:
        """Genera una respuesta citada usando contexto recuperado + LLM."""
        documents = await self.query(question, k)
        sources = [
            {
                "source": doc.metadata.get("source"),
                "page": doc.metadata.get("page"),
                "content": doc.page_content,
            }
            for doc in documents
        ]
        context = "\n\n".join(
            f"[Fuente {i + 1}]\n{doc.page_content}" for i, doc in enumerate(documents)
        )
        answer_text = (
            "No pude generar una respuesta. Verifica que el índice RAG y Ollama "
            "estén disponibles."
        )
        try:
            response = self.llm.invoke(self._build_prompt(context, question))
            answer_text = str(response.content)
        except Exception:
            logger.warning("No se pudo invocar el LLM; se responde sin generación")
        return {"answer": answer_text, "sources": sources}

    async def suggest_remediation(
        self,
        control_code: str,
        control_description: str,
        function_name: str | None,
        category_name: str | None,
        subcategory_name: str | None,
        current_level: int,
        target_level: int,
    ) -> dict[str, Any]:
        """Sugiere pasos concretos para cerrar una brecha puntual del catálogo MCU 5.0.

        A diferencia de answer(), no se restringe a "solo con base en los fragmentos
        recuperados": acá el objetivo es una guía práctica y accionable para la organización,
        no una respuesta citada -- se apoya en el contexto del propio MCU 5.0 si está indexado,
        pero puede completar con buenas prácticas generales de ciberseguridad cuando el
        catálogo no alcanza (a diferencia de evaluate_evidence, que sí necesita ceñirse
        estrictamente a la evidencia real aportada).
        """
        documents = await self.query(f"{control_code}: {control_description}", k=3)
        context = "\n\n".join(doc.page_content for doc in documents)
        prompt = self._build_remediation_prompt(
            control_code,
            control_description,
            function_name,
            category_name,
            subcategory_name,
            current_level,
            target_level,
            context,
        )
        try:
            response = self.llm.invoke(prompt)
            return self._parse_remediation(str(response.content))
        except Exception:
            logger.warning("No se pudo invocar el LLM para sugerir remediación")
            return {
                "summary": "",
                "tips": [
                    "No se pudo generar una sugerencia automática en este momento -- "
                    "verificá que Ollama esté disponible e intentá de nuevo."
                ],
            }

    async def ingest(self, text: str, source: str | None = None) -> int:
        """Divide e indexa un documento nuevo. Devuelve el nº de fragmentos."""
        if not text or not text.strip():
            return 0
        chunks = self._split().split_text(text)
        if not chunks:
            return 0
        try:
            if self._vectorstore is None:
                self._vectorstore = self._connect()
            metadata = {"source": source or "ingest"}
            documents = [
                Document(page_content=chunk, metadata={**metadata, "chunk": i})
                for i, chunk in enumerate(chunks)
            ]
            self._vectorstore.add_documents(documents)
            return len(chunks)
        except Exception:
            logger.exception("Falla la ingesta de documento")
            return 0

    def status(self) -> dict[str, Any]:
        """Estado del índice para el endpoint de health del RAG."""
        try:
            count = self._collection_count()
        except Exception:
            logger.exception("No se pudo consultar el conteo de la colección")
            count = 0
        return {
            "ready": self._vectorstore is not None and count > 0,
            "collection": self.collection_name,
            "documents": count,
        }

    # ------------------------------------------------------------------
    # Internos
    # ------------------------------------------------------------------
    def _connect(self) -> Chroma:
        # Ver app/rag/chroma_client.py: cliente HTTP contra un servidor Chroma aparte si
        # CHROMA_SERVER_HOST está seteado (Kubernetes, backend-ai con más de una réplica);
        # PersistentClient local si no (docker-compose, tests).
        return Chroma(
            client=build_chroma_client(self.settings),
            collection_name=self.collection_name,
            embedding_function=self.embeddings,
        )

    def _collection_count(self) -> int:
        client = build_chroma_client(self.settings)
        return client.get_or_create_collection(self.collection_name).count()

    def _split(self) -> RecursiveCharacterTextSplitter:
        return loaders.build_splitter(self.settings.rag_chunk_size, self.settings.rag_chunk_overlap)

    def _load_documents(self, docs_dir: Path) -> list[Document]:
        if not docs_dir.is_dir():
            logger.warning("Directorio de documentos no encontrado: %s", docs_dir)
            return []
        documents: list[Document] = []
        for path in sorted(p for p in docs_dir.rglob("*") if p.is_file()):
            documents.extend(loaders.load_file(path))
        return documents

    def _build_prompt(self, context: str, question: str) -> str:
        return (
            "Eres un asistente experto del Marco de Ciberseguridad MCU 5.0 de PRISMA. "
            "Responde en español, de forma concisa y únicamente con base en los "
            "fragmentos provistos. Si no hay información suficiente, indícalo. "
            "Cita siempre la fuente entre corchetes.\n\n"
            f"CONTEXTO:\n{context or '(sin contexto recuperado)'}\n\n"
            f"PREGUNTA:\n{question}"
        )

    def _build_remediation_prompt(
        self,
        code: str,
        description: str,
        function_name: str | None,
        category_name: str | None,
        subcategory_name: str | None,
        current_level: int,
        target_level: int,
        context: str,
    ) -> str:
        location = " › ".join(p for p in (function_name, category_name, subcategory_name) if p)
        context_block = f"Contexto del marco MCU 5.0 relacionado:\n{context}\n\n" if context else ""
        return (
            "Eres un consultor de ciberseguridad que asesora a organizaciones uruguayas -- "
            "muchas veces pequeñas o medianas, con equipos de TI chicos -- que están "
            "implementando el Marco de Ciberseguridad MCU 5.0 de AGESIC.\n\n"
            f"La organización tiene una brecha en el control {code}"
            f"{f' ({location})' if location else ''}: \"{description}\".\n"
            f"Nivel actual: {current_level}. Nivel objetivo: {target_level}.\n\n"
            f"{context_block}"
            "Dame:\n"
            "1. Un resumen de una sola oración de qué hay que lograr.\n"
            "2. Entre 3 y 5 pasos concretos y accionables para cerrar esta brecha, pensados "
            "para un equipo con recursos limitados (herramientas o prácticas concretas, no "
            "generalidades). Un paso por línea, cada uno empezando con '- '.\n\n"
            "Respondé en español, sin rodeos ni introducciones."
        )

    def _parse_remediation(self, text: str) -> dict[str, Any]:
        lines = [line.strip() for line in text.splitlines() if line.strip()]
        tips = [
            self._strip_list_marker(line)
            for line in lines
            if line.lstrip().startswith(("-", "•", "*"))
        ]
        summary_lines = [line for line in lines if not line.lstrip().startswith(("-", "•", "*"))]
        summary = self._strip_list_marker(summary_lines[0]) if summary_lines else ""
        if not tips:
            # El LLM no siguió el formato de lista pedido: mejor devolver el texto completo
            # como sugerencia que dejar la lista de tips vacía silenciosamente.
            tips = lines or [text.strip()]
        return {"summary": summary, "tips": tips}

    @staticmethod
    def _strip_list_marker(line: str) -> str:
        """Saca viñetas ('- ', '• ') y numeración ('1.', '2)') que el LLM devuelve pese a
        pedirle un resumen de una sola oración -- si no, "1. Lograr X" queda tal cual en vez
        de "Lograr X"."""
        return re.sub(r"^[-•*]\s*|^\d+[.)]\s*", "", line).strip()
