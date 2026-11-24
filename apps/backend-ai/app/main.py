"""PRISMA — Servicio de IA / RAG.

FastAPI que actúa como intermediario entre PRISMA y Ollama (Llama 3),
implementando un pipeline RAG sobre la documentación del MCU 5.0.
"""

from __future__ import annotations

import asyncio
import logging
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from prometheus_fastapi_instrumentator import Instrumentator

from app.api.v1 import router as api_router
from app.core.config import get_settings
from app.core.logging import setup_logging
from app.rag.evidence_store import EvidenceStore, IndexSearchError
from app.rag.ollama_runtime import (
    PromptTooLargeError,
    keep_warm,
    refresh_effective_context,
    supports_thinking,
)
from app.rag.vector_store import VectorStore
from app.version import __version__, normalize_environment

setup_logging()
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Inicializa los vector stores al arrancar y libera recursos al cerrar."""
    cfg = get_settings()
    think_supported = await supports_thinking(cfg) if cfg.ai_think else False
    logger.info("Inicializando VectorStore RAG...")
    app.state.vector_store = VectorStore(think_supported=think_supported)
    await app.state.vector_store.initialize()
    logger.info("VectorStore listo.")
    # EvidenceStore no indexa nada al arrancar (a diferencia de VectorStore, que carga
    # docs/mcu-5.0): las colecciones por organizacion se crean sobre la marcha, en el primer
    # ingest de esa organizacion.
    app.state.evidence_store = EvidenceStore()
    # Precalentamiento en segundo plano (no bloquea el arranque ni el health): carga LLM y
    # embedding en Ollama con las mismas opciones que usarán los pedidos reales.
    app.state.warmup = {"status": "disabled"}
    warmup_task: asyncio.Task[None] | None = None
    if cfg.ai_warmup:
        warmup_task = asyncio.create_task(keep_warm(cfg, app.state.warmup))
    else:
        # Sin precalentamiento igual hay que conocer el contexto efectivo del modelo: de eso
        # depende el límite del prompt (ver ollama_runtime.prompt_limit).
        warmup_task = asyncio.create_task(refresh_effective_context(cfg))
    yield
    if warmup_task is not None:
        warmup_task.cancel()
    logger.info("Apagando VectorStore...")
    await app.state.vector_store.close()


settings = get_settings()

app = FastAPI(
    title="PRISMA — AI Service",
    description="Servicio de IA y RAG sobre el Marco de Ciberseguridad 5.0.",
    version=__version__,
    docs_url="/docs",
    redoc_url="/redoc",
    openapi_url="/openapi.json",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(api_router, prefix="/api/v1")


@app.exception_handler(IndexSearchError)
async def index_search_error_handler(_request: Request, exc: IndexSearchError) -> JSONResponse:
    """El índice de evidencia falló: error recuperable (503), no «sin resultados» con 200.
    backend-core lo trata como IA no disponible (reintenta o usa lo guardado)."""
    return JSONResponse(
        status_code=503,
        content={"code": exc.code, "detail": str(exc), "retryable": True},
    )


@app.exception_handler(PromptTooLargeError)
async def prompt_too_large_handler(_request: Request, exc: PromptTooLargeError) -> JSONResponse:
    """El prompt no entra en el contexto: se rechaza antes de llegar a Ollama (que lo recortaría
    en silencio). backend-core distingue este caso por ``code``."""
    return JSONResponse(
        status_code=413,
        content={
            "code": exc.code,
            "detail": str(exc),
            "estimated_tokens": exc.estimated_tokens,
            "limit_tokens": exc.limit_tokens,
        },
    )


# Expone /metrics en formato Prometheus (latencia y conteo de requests por
# endpoint/metodo/status, tamano de payloads, requests en curso). "prometheus-client"
# ya estaba declarado como dependencia pero nunca se habia montado ningun endpoint que
# lo usara, asi que prometheus.yml scrapeaba /metrics y recibia 404. instrument() debe
# llamarse antes de exponer para que la instrumentacion se registre en todas las rutas.
Instrumentator().instrument(app).expose(app, endpoint="/metrics", include_in_schema=False)


@app.get("/health", tags=["Health"])
async def health() -> dict[str, str]:
    return {
        "status": "ok",
        "service": "backend-ai",
        "ollama": settings.ollama_host,
        "version": __version__,
        "environment": normalize_environment(get_settings().app_env),
    }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",  # noqa: S104
        port=int(os.getenv("PORT", "8000")),
        reload=os.getenv("RELOAD", "false").lower() == "true",
    )
