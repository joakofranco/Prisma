"""Endpoints del pipeline RAG del MCU 5.0 y evaluación de evidencias."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app.core.security import verify_internal_key
from app.rag.vector_store import VectorStore  # noqa: F401

logger = logging.getLogger(__name__)

# Cada cuánto se revisa si el cliente (backend-core) cortó la conexión mientras el LLM genera.
DISCONNECT_POLL_SECONDS = 1.0
# Código no estándar (nginx) para «el cliente cerró la conexión»: solo queda en los logs.
CLIENT_CLOSED_REQUEST = 499


async def run_until_disconnect[T](request: Request, work: Awaitable[T]) -> T | JSONResponse:
    """Ejecuta ``work`` y lo CANCELA si el cliente corta la conexión antes de que termine.

    backend-core corta por timeout y sigue con su vida, pero sin esto backend-ai y Ollama seguirían
    generando una respuesta que nadie va a leer, ocupando una de las 2 ranuras de Ollama y
    empeorando los tiempos de los trabajos siguientes. Cancelar la tarea cierra la conexión con
    Ollama (ver ``VectorStore._invoke_llm``), que aborta la generación.
    """
    task = asyncio.ensure_future(work)
    abandoned = False

    async def watch() -> None:
        nonlocal abandoned
        while not task.done():
            if await request.is_disconnected():
                abandoned = True
                task.cancel()
                return
            await asyncio.sleep(DISCONNECT_POLL_SECONDS)

    watcher = asyncio.create_task(watch())
    try:
        return await task
    except asyncio.CancelledError:
        if not abandoned:
            raise  # cancelación ajena (apagado del servidor): se propaga
        logger.info("Cliente desconectado: se abortó la generación en curso")
        return JSONResponse(status_code=CLIENT_CLOSED_REQUEST, content={"detail": "client closed"})
    finally:
        watcher.cancel()


class RagQueryRequest(BaseModel):
    question: str = Field(min_length=1, max_length=4000)
    k: int | None = Field(default=None, ge=1, le=20)


class RagQueryResponse(BaseModel):
    answer: str
    sources: list[dict[str, Any]]


class RagIngestRequest(BaseModel):
    text: str = Field(min_length=1)
    source: str | None = None


class RagStatusResponse(BaseModel):
    ready: bool
    collection: str
    documents: int


class RemediationTipsRequest(BaseModel):
    control_code: str = Field(min_length=1)
    control_description: str = Field(min_length=1, max_length=4000)
    function_name: str | None = None
    category_name: str | None = None
    subcategory_name: str | None = None
    current_level: int = Field(ge=0, le=4)
    target_level: int = Field(ge=0, le=4)


class RemediationTipsResponse(BaseModel):
    summary: str
    tips: list[str]
    # False = el LLM no respondió y los tips son un texto de reemplazo (no una guía).
    generated: bool = True
    model: str | None = None
    # Tiempos (ns) y tokens de Ollama; ver VectorStore._llm_metrics.
    metrics: dict[str, Any] = Field(default_factory=dict)


class EvaluateEvidenceRequest(BaseModel):
    control_id: str | None = None
    control_description: str = Field(min_length=1, max_length=4000)
    evidence_text: str = Field(min_length=1, max_length=20000)


class EvaluateEvidenceResponse(BaseModel):
    conclusion: str
    rationale: str
    suggestion: str | None = None
    sources: list[dict[str, Any]]


def _store(request: Request) -> VectorStore:
    store: VectorStore | None = getattr(request.app.state, "vector_store", None)
    if store is None:
        raise HTTPException(status_code=503, detail="El índice RAG aún no está disponible")
    return store


rag_router = APIRouter(prefix="/rag", tags=["RAG"], dependencies=[Depends(verify_internal_key)])
evaluate_router = APIRouter(tags=["RAG"], dependencies=[Depends(verify_internal_key)])


@rag_router.get("/status", response_model=RagStatusResponse)
async def rag_status(request: Request) -> RagStatusResponse:
    status = _store(request).status()
    return RagStatusResponse(**status)


@rag_router.post("/query", response_model=RagQueryResponse)
async def rag_query(request: Request, body: RagQueryRequest) -> RagQueryResponse:
    result = await _store(request).answer(body.question, body.k)
    return RagQueryResponse(**result)


@rag_router.post("/ingest", response_model=RagStatusResponse)
async def rag_ingest(request: Request, body: RagIngestRequest) -> RagStatusResponse:
    await _store(request).ingest(body.text, body.source)
    status = _store(request).status()
    return RagStatusResponse(**status)


@rag_router.post("/remediation-tips", response_model=RemediationTipsResponse)
async def remediation_tips(
    request: Request, body: RemediationTipsRequest
) -> RemediationTipsResponse | JSONResponse:
    """Sugiere pasos concretos para cerrar una brecha detectada en un control puntual.

    Si el cliente corta la conexión mientras el LLM genera, se aborta la generación.
    """
    result = await run_until_disconnect(
        request,
        _store(request).suggest_remediation(
            control_code=body.control_code,
            control_description=body.control_description,
            function_name=body.function_name,
            category_name=body.category_name,
            subcategory_name=body.subcategory_name,
            current_level=body.current_level,
            target_level=body.target_level,
        ),
    )
    if isinstance(result, JSONResponse):
        return result
    return RemediationTipsResponse(**result)


@evaluate_router.post("/evaluate/evidence", response_model=EvaluateEvidenceResponse)
async def evaluate_evidence(
    request: Request, body: EvaluateEvidenceRequest
) -> EvaluateEvidenceResponse:
    """Juzga si una evidencia satisface un control usando contexto del MCU 5.0."""
    store = _store(request)
    question = (
        f"¿La siguiente evidencia satisface el control {body.control_id or ''} "
        f"descrito como: {body.control_description}?\n"
        f"Evidencia aportada:\n{body.evidence_text}\n"
        "Responde con una conclusión a favor o en contra y una justificación."
    )
    result = await store.answer(question)
    return EvaluateEvidenceResponse(
        conclusion=(
            "evidencia suficiente" if _supports(result["answer"]) else "evidencia insuficiente"
        ),
        rationale=result["answer"],
        suggestion=None,
        sources=result["sources"],
    )


def _supports(answer: str) -> bool:
    lowered = answer.lower()
    positive = ("satisface", "sí", "si cumple", "cumple", "suficiente", "adecuada")
    negative = ("no satisface", "no cumple", "insuficiente", "no hay", "insuficiente para")
    if any(word in lowered for word in negative):
        return False
    return any(word in lowered for word in positive)
