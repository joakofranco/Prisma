"""Opciones de Ollama por pedido, control de contexto, precalentamiento e info de runtime.

Reglas (ver .claude/plans/03, WP0/WP1):

* ``num_ctx`` y ``keep_alive`` son SIEMPRE los mismos (LLM, embedding y precalentamiento). Si
  cambian entre pedidos, Ollama recarga el modelo y el usuario paga el arranque en frío.
* Si el prompt supera ``num_ctx``, Ollama lo recorta a la mitad SIN avisar. Por eso se estima el
  tamaño antes de enviar y, si no entra, se levanta ``PromptTooLargeError`` (nunca se trunca en
  silencio ni llega a Ollama).
* Cambiar ``ollama_embedding_model`` exige reindexar todas las colecciones (cambian las
  dimensiones del vector).
"""

from __future__ import annotations

import asyncio
import logging
import math
import time
from typing import Any

import httpx

from app.core.config import Settings

logger = logging.getLogger(__name__)

PROMPT_TOO_LARGE = "PROMPT_TOO_LARGE"

# Estimación conservadora para español con tokenizadores tipo llama3 (~3,5 caracteres por
# token): se divide por 3 para sobreestimar un poco y no quedarse corto.
_CHARS_PER_TOKEN = 3

_HTTP_TIMEOUT_SECONDS = 10.0
_WARMUP_TIMEOUT_SECONDS = 300.0
_WARMUP_RETRY_SECONDS = 5.0
_WARMUP_MAX_ATTEMPTS = 120  # ~10 minutos esperando a que Ollama responda


class PromptTooLargeError(Exception):
    """El prompt estimado no entra en el contexto configurado."""

    code = PROMPT_TOO_LARGE

    def __init__(self, estimated_tokens: int, limit_tokens: int) -> None:
        self.estimated_tokens = estimated_tokens
        self.limit_tokens = limit_tokens
        super().__init__(
            f"El prompt estimado ({estimated_tokens} tokens) supera el límite "
            f"({limit_tokens} tokens) del contexto configurado."
        )


def estimate_tokens(text: str) -> int:
    """Cuenta aproximada de tokens (sin tokenizador, para no sumar dependencias)."""
    return math.ceil(len(text) / _CHARS_PER_TOKEN)


def guard_prompt(settings: Settings, prompt: str) -> int:
    """Devuelve los tokens estimados o levanta PromptTooLargeError si no entra."""
    estimated = estimate_tokens(prompt)
    limit = settings.prompt_max_tokens
    if estimated > limit:
        raise PromptTooLargeError(estimated, limit)
    return estimated


def keep_alive_value(settings: Settings) -> int | str:
    """``-1`` como entero (para siempre en Ollama); duraciones como "5m" tal cual."""
    raw = settings.ai_keep_alive.strip()
    try:
        return int(raw)
    except ValueError:
        return raw


def chat_kwargs(settings: Settings, think_supported: bool | None = None) -> dict[str, Any]:
    """Argumentos de ChatOllama. Los opcionales solo se incluyen si están definidos."""
    kwargs: dict[str, Any] = {
        "num_ctx": settings.ai_num_ctx,
        "keep_alive": keep_alive_value(settings),
        "num_predict": settings.ai_num_predict,
    }
    if settings.ai_temperature is not None:
        kwargs["temperature"] = settings.ai_temperature
    if settings.ai_seed is not None:
        kwargs["seed"] = settings.ai_seed
    if settings.ai_num_thread is not None:
        kwargs["num_thread"] = settings.ai_num_thread
    if settings.ai_think and think_supported:
        kwargs["reasoning"] = True
    return kwargs


def embedding_kwargs(settings: Settings) -> dict[str, Any]:
    """Argumentos de OllamaEmbeddings: mismas opciones de carga que el LLM."""
    kwargs: dict[str, Any] = {
        "num_ctx": settings.ai_num_ctx,
        "keep_alive": keep_alive_value(settings),
    }
    if settings.ai_num_thread is not None:
        kwargs["num_thread"] = settings.ai_num_thread
    return kwargs


def effective_options(settings: Settings) -> dict[str, Any]:
    """Opciones que realmente se envían, para el endpoint de runtime."""
    options = chat_kwargs(settings, think_supported=None)
    options.pop("reasoning", None)
    options["think_requested"] = settings.ai_think
    options["prompt_max_tokens"] = settings.prompt_max_tokens
    options["embedding_model"] = settings.ollama_embedding_model
    options["llm_model"] = settings.ollama_model
    return options


# ----------------------------------------------------------------------
# Consultas a Ollama (capabilities, runtime)
# ----------------------------------------------------------------------
async def fetch_show(client: httpx.AsyncClient, host: str, model: str) -> dict[str, Any]:
    response = await client.post(f"{host}/api/show", json={"model": model})
    response.raise_for_status()
    data: dict[str, Any] = response.json()
    return data


async def supports_thinking(settings: Settings) -> bool:
    """True si el LLM declara la capability ``thinking`` (consulta ``/api/show``)."""
    try:
        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT_SECONDS) as client:
            show = await fetch_show(client, settings.ollama_host, settings.ollama_model)
    except Exception:
        logger.warning("No se pudo consultar capabilities de %s", settings.ollama_model)
        return False
    return "thinking" in (show.get("capabilities") or [])


async def runtime_info(settings: Settings, warmup: dict[str, Any] | None = None) -> dict[str, Any]:
    """Modelo, digest, versión de Ollama, opciones efectivas y estado de carga.

    Nunca lanza: si Ollama no responde, ``ollama.reachable`` es False y el resto de las
    opciones configuradas igual se informa.
    """
    info: dict[str, Any] = {
        "options": effective_options(settings),
        "warmup": warmup or {"status": "disabled" if not settings.ai_warmup else "unknown"},
        "ollama": {"host": settings.ollama_host, "reachable": False},
        "models": {},
    }
    host = settings.ollama_host
    try:
        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT_SECONDS) as client:
            version = (await client.get(f"{host}/api/version")).json().get("version")
            ps = (await client.get(f"{host}/api/ps")).json().get("models", [])
            tags = (await client.get(f"{host}/api/tags")).json().get("models", [])
            info["ollama"] = {"host": host, "reachable": True, "version": version}
            loaded = {m.get("name"): m for m in ps}
            digests = {m.get("name"): m.get("digest") for m in tags}
            for role, name in (
                ("llm", settings.ollama_model),
                ("embedding", settings.ollama_embedding_model),
            ):
                entry: dict[str, Any] = {
                    "name": name,
                    "digest": _lookup(digests, name),
                    "loaded": False,
                }
                running = _lookup(loaded, name)
                if running:
                    entry.update(
                        loaded=True,
                        size_bytes=running.get("size"),
                        size_vram_bytes=running.get("size_vram"),
                        context_length=running.get("context_length"),
                        expires_at=running.get("expires_at"),
                    )
                try:
                    show = await fetch_show(client, host, name)
                    entry["capabilities"] = show.get("capabilities") or []
                except Exception:
                    entry["capabilities"] = None
                info["models"][role] = entry
    except Exception as exc:
        logger.warning("No se pudo consultar el runtime de Ollama: %s", exc)
        info["ollama"]["error"] = type(exc).__name__
    return info


def _lookup(by_name: dict[Any, Any], name: str) -> Any:
    """Ollama lista ``llama3:8b`` pero también ``nomic-embed-text:latest``: matchea ambos."""
    if name in by_name:
        return by_name[name]
    return by_name.get(f"{name}:latest")


# ----------------------------------------------------------------------
# Precalentamiento
# ----------------------------------------------------------------------
def _warmup_llm_payload(settings: Settings) -> dict[str, Any]:
    options: dict[str, Any] = {"num_ctx": settings.ai_num_ctx}
    if settings.ai_num_thread is not None:
        options["num_thread"] = settings.ai_num_thread
    # Pedido vacío: Ollama solo carga el modelo, no genera nada.
    return {
        "model": settings.ollama_model,
        "prompt": "",
        "keep_alive": keep_alive_value(settings),
        "options": options,
    }


def _warmup_embed_payload(settings: Settings) -> dict[str, Any]:
    options: dict[str, Any] = {"num_ctx": settings.ai_num_ctx}
    if settings.ai_num_thread is not None:
        options["num_thread"] = settings.ai_num_thread
    return {
        "model": settings.ollama_embedding_model,
        "input": "warmup",
        "keep_alive": keep_alive_value(settings),
        "options": options,
    }


async def models_loaded(settings: Settings) -> bool | None:
    """True si LLM y embedding figuran cargados en ``/api/ps``; None si Ollama no responde."""
    try:
        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT_SECONDS) as client:
            response = await client.get(f"{settings.ollama_host}/api/ps")
            response.raise_for_status()
            loaded = {m.get("name"): m for m in response.json().get("models", [])}
    except Exception:
        return None
    return bool(
        _lookup(loaded, settings.ollama_model) and _lookup(loaded, settings.ollama_embedding_model)
    )


async def keep_warm(settings: Settings, state: dict[str, Any]) -> None:
    """Precalienta al arrancar y después vigila: si Ollama se reinició y descargó los modelos, los
    vuelve a cargar sin esperar a que un usuario pague la carga en frío."""
    await warmup(settings, state)
    interval = settings.ai_warmup_check_seconds
    if interval <= 0:
        return
    while True:
        await asyncio.sleep(interval)
        if await models_loaded(settings) is False:
            logger.info("Los modelos ya no están cargados en Ollama: se vuelven a precalentar")
            state["reloads"] = int(state.get("reloads", 0)) + 1
            await warmup(settings, state)


async def warmup(settings: Settings, state: dict[str, Any]) -> None:
    """Carga LLM y embedding con las mismas opciones que usará la app.

    Reintenta mientras Ollama no responda. Actualiza ``state`` (visible en el endpoint de
    runtime). Nunca lanza: un fallo de precalentamiento no tumba el servicio.
    """
    state.update(status="running", attempts=0, llm_ms=None, embedding_ms=None, error=None)
    host = settings.ollama_host
    async with httpx.AsyncClient(timeout=_WARMUP_TIMEOUT_SECONDS) as client:
        for attempt in range(1, _WARMUP_MAX_ATTEMPTS + 1):
            state["attempts"] = attempt
            try:
                if state.get("llm_ms") is None:
                    started = time.monotonic()
                    r = await client.post(
                        f"{host}/api/generate", json=_warmup_llm_payload(settings)
                    )
                    r.raise_for_status()
                    state["llm_ms"] = round((time.monotonic() - started) * 1000)
                if state.get("embedding_ms") is None:
                    started = time.monotonic()
                    r = await client.post(f"{host}/api/embed", json=_warmup_embed_payload(settings))
                    r.raise_for_status()
                    state["embedding_ms"] = round((time.monotonic() - started) * 1000)
                state["status"] = "ready"
                logger.info(
                    "Precalentamiento listo (llm %s ms, embedding %s ms)",
                    state["llm_ms"],
                    state["embedding_ms"],
                )
                return
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                state["error"] = f"{type(exc).__name__}: {exc}"
                logger.warning("Precalentamiento: intento %d falló (%s)", attempt, state["error"])
                await asyncio.sleep(_WARMUP_RETRY_SECONDS)
    state["status"] = "failed"
    logger.error("Precalentamiento abandonado tras %d intentos", _WARMUP_MAX_ATTEMPTS)
