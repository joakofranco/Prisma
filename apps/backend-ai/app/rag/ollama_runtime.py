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


# ----------------------------------------------------------------------
# Contexto efectivo (el que Ollama realmente carga)
# ----------------------------------------------------------------------
# ``AI_NUM_CTX`` es lo que se PIDE; el modelo puede tener un máximo menor (llama3:8b: 8192,
# nomic-embed-text: 2048) y Ollama carga con ese. Si el límite del prompt se calculara con lo
# pedido, un prompt de 9 000 tokens pasaría el control y Ollama lo recortaría en silencio. Por eso
# se guarda el contexto efectivo (de ``/api/ps``, o del máximo del modelo de ``/api/show`` si aún
# no está cargado) y se usa ``min(AI_NUM_CTX, efectivo)``.
_effective_context: dict[str, tuple[int, str]] = {}


def set_effective_context(role: str, value: int | None, source: str) -> None:
    if value is not None and value > 0:
        _effective_context[role] = (value, source)


def get_effective_context(role: str) -> int | None:
    entry = _effective_context.get(role)
    return entry[0] if entry else None


def reset_effective_context() -> None:
    _effective_context.clear()


def llm_context(settings: Settings) -> int:
    """``min(AI_NUM_CTX, contexto efectivo del LLM)``; sin dato del modelo, lo pedido."""
    effective = get_effective_context("llm")
    return min(settings.ai_num_ctx, effective) if effective else settings.ai_num_ctx


def prompt_limit(settings: Settings) -> int:
    """Tokens máximos del prompt: contexto efectivo menos la respuesta y el margen.

    Un ``AI_PROMPT_MAX_TOKENS`` explícito solo puede ACHICAR el límite, nunca pasar el que permite
    el contexto efectivo.
    """
    auto = max(
        1, llm_context(settings) - settings.ai_num_predict - settings.ai_prompt_margin_tokens
    )
    if settings.ai_prompt_max_tokens is not None:
        return min(settings.ai_prompt_max_tokens, auto)
    return auto


def embedding_input_limit(settings: Settings) -> int | None:
    """Tokens máximos de un texto a embeber (contexto efectivo del embedding), si se conoce."""
    effective = get_effective_context("embedding")
    if effective is None:
        return None
    return max(1, min(settings.ai_num_ctx, effective) - settings.ai_prompt_margin_tokens)


def guard_prompt(settings: Settings, prompt: str) -> int:
    """Devuelve los tokens estimados o levanta PromptTooLargeError si no entra."""
    estimated = estimate_tokens(prompt)
    limit = prompt_limit(settings)
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
    options["prompt_max_tokens"] = prompt_limit(settings)
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


def _model_max_context(show: dict[str, Any]) -> int | None:
    """Máximo de contexto del modelo según ``/api/show`` (``<arquitectura>.context_length``)."""
    for key, value in (show.get("model_info") or {}).items():
        if str(key).endswith(".context_length") and isinstance(value, int):
            return value
    return None


async def refresh_effective_context(settings: Settings) -> None:
    """Actualiza el contexto efectivo de LLM y embedding. Nunca lanza.

    Primero ``/api/ps`` (lo que Ollama cargó de verdad); si el modelo aún no está cargado, el máximo
    del modelo de ``/api/show`` (cota superior: Ollama no carga con más que eso).
    """
    try:
        async with httpx.AsyncClient(timeout=_HTTP_TIMEOUT_SECONDS) as client:
            host = settings.ollama_host
            response = await client.get(f"{host}/api/ps")
            response.raise_for_status()
            loaded = {m.get("name"): m for m in response.json().get("models", [])}
            for role, name in (
                ("llm", settings.ollama_model),
                ("embedding", settings.ollama_embedding_model),
            ):
                running = _lookup(loaded, name)
                if running and running.get("context_length"):
                    set_effective_context(role, running["context_length"], "ps")
                elif get_effective_context(role) is None:
                    try:
                        show = await fetch_show(client, host, name)
                    except Exception as exc:
                        logger.debug("Sin /api/show de %s: %s", name, exc)
                        continue
                    set_effective_context(role, _model_max_context(show), "show")
    except Exception as exc:
        logger.debug("No se pudo refrescar el contexto efectivo: %s", exc)
        return
    _warn_on_context_mismatch(settings)


def _warn_on_context_mismatch(settings: Settings) -> None:
    llm = get_effective_context("llm")
    if llm is not None and llm < settings.ai_num_ctx:
        logger.warning(
            "El LLM %s carga con contexto %d, menor que AI_NUM_CTX=%d: el límite del prompt se "
            "calcula con %d",
            settings.ollama_model,
            llm,
            settings.ai_num_ctx,
            llm,
        )
    limit = embedding_input_limit(settings)
    if limit is not None and estimate_tokens("x" * settings.rag_chunk_size) > limit:
        logger.warning(
            "RAG_CHUNK_SIZE=%d (≈%d tokens) no entra en el contexto del embedding (%d): "
            "Ollama recortaría los fragmentos al indexarlos",
            settings.rag_chunk_size,
            estimate_tokens("x" * settings.rag_chunk_size),
            limit,
        )


def context_report(settings: Settings) -> dict[str, Any]:
    """Diferencia entre el contexto pedido y el efectivo, para ``/api/v1/runtime``."""
    entries = {role: _effective_context.get(role) for role in ("llm", "embedding")}
    llm = entries["llm"]
    return {
        "requested_num_ctx": settings.ai_num_ctx,
        "llm": {
            "effective": llm[0] if llm else None,
            "source": llm[1] if llm else None,
            "used_for_limit": llm_context(settings),
        },
        "embedding": {
            "effective": entries["embedding"][0] if entries["embedding"] else None,
            "source": entries["embedding"][1] if entries["embedding"] else None,
            "input_max_tokens": embedding_input_limit(settings),
        },
        "prompt_max_tokens": prompt_limit(settings),
        # True = Ollama carga el LLM con menos contexto que el pedido (AI_NUM_CTX está de más).
        "mismatch": bool(llm and llm[0] < settings.ai_num_ctx),
    }


async def runtime_info(settings: Settings, warmup: dict[str, Any] | None = None) -> dict[str, Any]:
    """Modelo, digest, versión de Ollama, opciones efectivas y estado de carga.

    Nunca lanza: si Ollama no responde, ``ollama.reachable`` es False y el resto de las
    opciones configuradas igual se informa.
    """
    # Primero el contexto efectivo: las opciones y el límite del prompt dependen de él.
    await refresh_effective_context(settings)
    info: dict[str, Any] = {
        "options": effective_options(settings),
        "warmup": warmup or {"status": "disabled" if not settings.ai_warmup else "unknown"},
        "ollama": {"host": settings.ollama_host, "reachable": False},
        "models": {},
    }
    host = settings.ollama_host
    info["context"] = context_report(settings)
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
    await refresh_effective_context(settings)
    interval = settings.ai_warmup_check_seconds
    if interval <= 0:
        return
    while True:
        await asyncio.sleep(interval)
        await refresh_effective_context(settings)
        if await models_loaded(settings) is False:
            logger.info("Los modelos ya no están cargados en Ollama: se vuelven a precalentar")
            state["reloads"] = int(state.get("reloads", 0)) + 1
            await warmup(settings, state)
            await refresh_effective_context(settings)


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
