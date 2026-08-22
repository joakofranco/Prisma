"""Autenticacion servicio-a-servicio entre backend-core y backend-ai.

backend-ai no valida JWTs de usuario ni conoce roles/tenants: quien decide que
organizacion/evaluacion corresponde a cada request es siempre backend-core (ya aplica
CurrentUserService.assertOrganizationAccess antes de llamar aca). Este modulo solo garantiza
que backend-ai no sea alcanzable por cualquiera que le pegue directo al puerto expuesto sin
conocer la clave compartida -- sin esto, el aislamiento por tenant dependeria enteramente de
que nadie mas llame a la API, lo cual no es una garantia real.
"""

from __future__ import annotations

import hmac

from fastapi import Header, HTTPException

from app.core.config import get_settings


async def verify_internal_key(x_internal_api_key: str | None = Header(default=None)) -> None:
    settings = get_settings()
    expected = settings.internal_api_key
    if not expected:
        # Sin clave configurada (dev/test local): no se aplica el chequeo.
        return
    if not x_internal_api_key or not hmac.compare_digest(x_internal_api_key, expected):
        raise HTTPException(status_code=401, detail="Invalid or missing internal API key")
