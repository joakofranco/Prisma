"""Pipeline RAG sobre la documentación del MCU 5.0.

``Documento → Loader → Splitter → Embeddings → ChromaDB``
``Pregunta → Embedding → Retriever (top-k) → LLM (Ollama) → Respuesta + citas``

El servicio arranca aunque Ollama o el directorio de documentos no estén
disponibles: ante cualquier fallo se registra el error y las consultas
devuelven contexto vacío en lugar de tumbar el proceso.
"""

from __future__ import annotations

import asyncio
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
from app.rag.collections import mcu_collection
from app.rag.ollama_runtime import chat_kwargs, embedding_kwargs, estimate_tokens, guard_prompt

logger = logging.getLogger(__name__)

SUPPORTED_EXTENSIONS = loaders.SUPPORTED_EXTENSIONS


class VectorStore:
    """Vector store RAG sobre ChromaDB con embeddings y LLM vía Ollama."""

    def __init__(
        self,
        settings: Settings | None = None,
        embeddings: Any | None = None,
        llm: Any | None = None,
        think_supported: bool = False,
    ) -> None:
        self.settings = settings or get_settings()
        self.embeddings = embeddings or OllamaEmbeddings(
            base_url=self.settings.ollama_host,
            model=self.settings.ollama_embedding_model,
            **embedding_kwargs(self.settings),
        )
        # num_ctx/keep_alive/num_predict (+ temperature/seed/num_thread si están definidos): ver
        # app/rag/ollama_runtime.py. "reasoning" solo si el modelo declara la capability.
        self.llm = llm or ChatOllama(
            base_url=self.settings.ollama_host,
            model=self.settings.ollama_model,
            **chat_kwargs(self.settings, think_supported),
        )
        # Una colección por modelo de embedding (ver app/rag/collections.py): cambiar de modelo
        # no pisa el índice anterior y se puede volver atrás.
        self.collection_name = mcu_collection(self.settings)
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
            logger.info(
                "Colección %s sin fragmentos (modelo de embedding %s): se indexa el marco",
                self.collection_name,
                self.settings.ollama_embedding_model,
            )
            docs_dir = Path(self.settings.rag_docs_dir)
            documents = self._load_documents(docs_dir)
            if not documents:
                logger.warning(
                    "Directorio %s sin documentos para indexar; la colección queda vacía",
                    self.settings.rag_docs_dir,
                )
                return
            chunks, ids = self._chunk_framework(documents)
            # IDs deterministicos, no los UUID random que Chroma generaria por default: con
            # backend-ai corriendo con mas de una replica contra el mismo servidor Chroma (ver
            # chroma_client.py), dos pods pueden ver la coleccion vacia a la vez en un arranque en
            # frio y disparar esta indexacion inicial en paralelo -- con IDs deterministicos, el
            # segundo add_documents() actualiza (upsert) los mismos fragmentos en vez de
            # duplicarlos.
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
        # Fuera del try: un prompt que no entra en el contexto es un error explícito
        # (PromptTooLargeError), no un "no pude generar" genérico ni un recorte silencioso.
        prompt = self._build_prompt(context, question)
        guard_prompt(self.settings, prompt)
        try:
            response = await self._invoke_llm(prompt)
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
        trace: bool = False,
        seed: int | None = None,
    ) -> dict[str, Any]:
        """Sugiere pasos concretos para cerrar una brecha puntual del catálogo MCU 5.0.

        A diferencia de answer(), no se restringe a "solo con base en los fragmentos
        recuperados": acá el objetivo es una guía práctica y accionable para la organización,
        no una respuesta citada -- se apoya en el contexto del propio MCU 5.0 si está indexado,
        pero puede completar con buenas prácticas generales de ciberseguridad cuando el
        catálogo no alcanza (a diferencia de evaluate_evidence, que sí necesita ceñirse
        estrictamente a la evidencia real aportada).
        """
        query_text = f"{control_code}: {control_description}"
        documents = await self.query(query_text, k=3)
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
        guard_prompt(self.settings, prompt)
        try:
            # En un hilo aparte: invoke() es bloqueante (decenas de segundos en CPU) y no debe
            # frenar el event loop (health, métricas y otros pedidos).
            response = await self._invoke_llm(prompt, seed=seed if trace else None)
            result = self._parse_remediation(str(response.content))
            result["generated"] = True
            result["model"] = self.settings.ollama_model
            result["metrics"] = self._llm_metrics(response, prompt)
            if trace:
                result["trace"] = await self._build_trace(
                    prompt=prompt,
                    response=response,
                    seed=seed,
                    query_text=query_text,
                    documents=documents,
                    metrics=result["metrics"],
                )
            return result
        except Exception:
            logger.warning("No se pudo invocar el LLM para sugerir remediación")
            # generated=False: backend-core (cola asíncrona) lo trata como falla reintentable en
            # vez de guardar este texto de reemplazo como si fuera una guía.
            return {
                "generated": False,
                "model": self.settings.ollama_model,
                "metrics": {},
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
    async def _build_trace(
        self,
        *,
        prompt: str,
        response: Any,
        seed: int | None,
        query_text: str,
        documents: list[Document],
        metrics: dict[str, Any],
    ) -> dict[str, Any]:
        """Traza del modo investigación (C8): configuración, semilla, prompt, respuesta cruda,
        razonamiento (si el modelo lo devolvió) y los fragmentos del marco que se recuperaron.

        Del catálogo MCU solo se guardan ids y puntajes de los fragmentos: es contenido público,
        pero igual no se duplica el texto.
        """
        from app.rag.ollama_runtime import trace_config

        retrieval: list[dict[str, Any]] = []
        scored = getattr(self._vectorstore, "similarity_search_with_score", None)
        if scored is not None:
            try:
                for doc, score in scored(query_text, k=3):
                    retrieval.append(
                        {
                            "source": doc.metadata.get("source"),
                            "id": getattr(doc, "id", None),
                            "score": float(score),
                        }
                    )
            except Exception:
                logger.warning("No se pudieron obtener los puntajes de recuperación para la traza")
        if not retrieval:
            retrieval = [
                {"source": d.metadata.get("source"), "id": getattr(d, "id", None)}
                for d in documents
            ]
        extra = getattr(response, "additional_kwargs", None) or {}
        return {
            "config": await trace_config(self.settings),
            "seed": seed,
            "prompt": prompt,
            "response": str(response.content),
            "thinking": extra.get("reasoning_content") or extra.get("thinking"),
            "retrieval": retrieval,
            "metrics": metrics,
        }

    async def _invoke_llm(self, prompt: str, seed: int | None = None) -> Any:
        """Llama al LLM sin bloquear el event loop y de forma CANCELABLE.

        Con ``ainvoke`` (ChatOllama) cancelar la tarea cierra la conexión HTTP con Ollama, y Ollama
        aborta la generación; con ``invoke`` en un hilo no se podría cortar y la respuesta
        abandonada seguiría ocupando una ranura (ver ``run_until_disconnect``). Los LLM sin
        ``ainvoke`` (simulados en los tests) caen al hilo.
        """
        llm = self.llm
        if seed is not None:
            # Semilla por pedido (modo investigación): una copia del modelo con la semilla, sin
            # tocar la instancia compartida. Los LLM simulados de los tests no la soportan.
            copy = getattr(llm, "model_copy", None)
            if copy is not None:
                llm = copy(update={"seed": seed})
        ainvoke = getattr(llm, "ainvoke", None)
        if ainvoke is not None:
            return await ainvoke(prompt)
        return await asyncio.to_thread(llm.invoke, prompt)

    def _connect(self) -> Chroma:
        # Ver app/rag/chroma_client.py: PersistentClient local.
        return Chroma(
            client=build_chroma_client(self.settings),
            collection_name=self.collection_name,
            embedding_function=self.embeddings,
        )

    def _collection_count(self) -> int:
        client = build_chroma_client(self.settings)
        return int(client.get_or_create_collection(self.collection_name).count())

    def _chunk_framework(self, documents: list[Document]) -> tuple[list[Document], list[str]]:
        """Fragmentos del marco con sus ids. Un fragmento por requisito (``req_ctx``) para los
        documentos con «### Requisito XX.N»; el resto se trocea de la forma genérica.

        Id del requisito: ``<fuente>::req-XX.N`` (estable: no depende del orden ni del tamaño).
        Id genérico: ``<fuente>::chunk-<n>``.
        """
        chunks: list[Document] = []
        ids: list[str] = []
        seen: set[str] = set()
        generic: list[Document] = []
        for doc in documents:
            source = str(doc.metadata.get("source", "doc"))
            fragments = loaders.split_by_requirement(doc)
            if not fragments:
                generic.append(doc)
                continue
            for fragment in fragments:
                fragment_id = f"{source}::req-{fragment.metadata['requirement']}"
                if fragment_id in seen:  # requisito repetido en el mismo documento: no se pisa
                    n = 2
                    while f"{fragment_id}-{n}" in seen:
                        n += 1
                    fragment_id = f"{fragment_id}-{n}"
                seen.add(fragment_id)
                chunks.append(fragment)
                ids.append(fragment_id)
        if generic:
            generic_chunks = self._split().split_documents(generic)
            for i, doc in enumerate(generic_chunks):
                chunks.append(doc)
                ids.append(f"{doc.metadata.get('source', 'doc')}::chunk-{i}")
        return chunks, ids

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
    def _llm_metrics(response: Any, prompt: str) -> dict[str, Any]:
        """Tiempos (ns) y tokens que Ollama informa en la respuesta, para la medición (WP3).

        Los modelos simulados de los tests no traen metadata: devuelve solo lo estimado.
        """
        meta = getattr(response, "response_metadata", None) or {}
        keys = (
            "total_duration",
            "load_duration",
            "prompt_eval_count",
            "prompt_eval_duration",
            "eval_count",
            "eval_duration",
        )
        metrics: dict[str, Any] = {k: meta[k] for k in keys if meta.get(k) is not None}
        metrics["prompt_tokens_estimated"] = estimate_tokens(prompt)
        return metrics

    @staticmethod
    def _strip_list_marker(line: str) -> str:
        """Saca viñetas ('- ', '• ') y numeración ('1.', '2)') que el LLM devuelve pese a
        pedirle un resumen de una sola oración -- si no, "1. Lograr X" queda tal cual en vez
        de "Lograr X"."""
        return re.sub(r"^[-•*]\s*|^\d+[.)]\s*", "", line).strip()
