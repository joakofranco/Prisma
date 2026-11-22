"""Estado del runtime de IA: modelo, digest, versión de Ollama, opciones efectivas y carga.

Sirve para la prueba en vivo y, más adelante, para la traza del modo investigación (C8).
Protegido con la clave interna, como el resto de la API.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, Request

from app.core.config import get_settings
from app.core.security import verify_internal_key
from app.rag.ollama_runtime import runtime_info

runtime_router = APIRouter(
    prefix="/runtime", tags=["Runtime"], dependencies=[Depends(verify_internal_key)]
)


@runtime_router.get("")
async def get_runtime(request: Request) -> dict[str, Any]:
    warmup_state = getattr(request.app.state, "warmup", None)
    return await runtime_info(get_settings(), warmup_state)
