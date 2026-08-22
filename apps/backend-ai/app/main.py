"""PRISMA — Servicio de IA / RAG.

FastAPI que actúa como intermediario entre PRISMA y Ollama (Llama 3),
implementando un pipeline RAG sobre la documentación del MCU 5.0.
"""

from __future__ import annotations

import logging
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from prometheus_fastapi_instrumentator import Instrumentator

from app.api.v1 import router as api_router
from app.core.config import get_settings
from app.core.logging import setup_logging
from app.rag.evidence_store import EvidenceStore
from app.rag.vector_store import VectorStore

setup_logging()
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Inicializa los vector stores al arrancar y libera recursos al cerrar."""
    logger.info("Inicializando VectorStore RAG...")
    app.state.vector_store = VectorStore()
    await app.state.vector_store.initialize()
    logger.info("VectorStore listo.")
    # EvidenceStore no indexa nada al arrancar (a diferencia de VectorStore, que carga
    # docs/mcu-5.0): las colecciones por organizacion se crean sobre la marcha, en el primer
    # ingest de esa organizacion.
    app.state.evidence_store = EvidenceStore()
    yield
    logger.info("Apagando VectorStore...")
    await app.state.vector_store.close()


settings = get_settings()

app = FastAPI(
    title="PRISMA — AI Service",
    description="Servicio de IA y RAG sobre el Marco de Ciberseguridad 5.0.",
    version="0.1.0",
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

# Expone /metrics en formato Prometheus (latencia y conteo de requests por
# endpoint/metodo/status, tamano de payloads, requests en curso). "prometheus-client"
# ya estaba declarado como dependencia pero nunca se habia montado ningun endpoint que
# lo usara, asi que prometheus.yml scrapeaba /metrics y recibia 404. instrument() debe
# llamarse antes de exponer para que la instrumentacion se registre en todas las rutas.
Instrumentator().instrument(app).expose(app, endpoint="/metrics", include_in_schema=False)


@app.get("/health", tags=["Health"])
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "backend-ai", "ollama": settings.ollama_host}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",  # noqa: S104
        port=int(os.getenv("PORT", "8000")),
        reload=os.getenv("RELOAD", "false").lower() == "true",
    )
