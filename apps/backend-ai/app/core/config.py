"""Configuración cargada desde variables de entorno."""

from functools import lru_cache

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    ollama_host: str = "http://ollama:11434"
    # Un solo LLM y un solo embedding (decisión 2026-11-22): no se dejan modelos alternativos.
    ollama_model: str = "nemotron-3-nano:30b"
    # Cada colección lleva el modelo en el nombre (mcu-5.0__bge-m3, evidence-<org>__bge-m3; ver
    # app/rag/collections.py): cambiar de modelo crea colecciones nuevas (Chroma no mezcla 768 con
    # 1024 dimensiones) y no pisa las anteriores. El marco se indexa solo al arrancar; la
    # evidencia la reindexa backend-core (un trabajo EVIDENCE_INDEX por evidencia).
    ollama_embedding_model: str = "bge-m3"
    # --- Opciones de Ollama enviadas en CADA pedido (ver app/rag/ollama_runtime.py) ---
    # num_ctx y keep_alive son siempre los mismos (LLM, embedding y precalentamiento): si
    # cambian entre pedidos Ollama recarga el modelo.
    ai_num_ctx: int = 16384
    ai_keep_alive: str = "-1"
    # Tope de tokens de la respuesta. Valor provisional [POR VERIFICAR]: definirlo con las guías
    # reales del hito 1 (una guía de 3-5 pasos ronda pocos cientos de tokens).
    ai_num_predict: int = 1024
    # 0,1 y 12 hilos son con lo que se midió el banco (perfil tuned_t12): fijarlos hace valer lo
    # medido y que la guía no cambie entre corridas (sin temperatura rige 0,8 de Ollama). Vacío
    # (AI_TEMPERATURE=) = no se envía y rige el valor del modelo. 12 hilos: [POR VERIFICAR] con
    # nemotron (se midió con llama3:8b).
    ai_temperature: float | None = 0.1
    ai_seed: int | None = None
    ai_num_thread: int | None = 12
    # Razonamiento. Solo se pide si el modelo declara la capability "thinking" (en modelos sin ella,
    # p. ej. llama3/gemma3, Ollama responde 400 si se lo pedís). Con nemotron-3-nano queda
    # apagado: la guía pasa de 39 a 91 s y la cita en QA de 100 % a 62 %.
    ai_think: bool = False
    # Pedidos simultáneos de Ollama (OLLAMA_NUM_PARALLEL del servidor): solo se informa en la traza.
    ollama_num_parallel: int | None = None
    # Precalentar LLM y embedding al arrancar (carga los modelos sin pedido de usuario).
    ai_warmup: bool = True
    # Cada cuántos segundos revisar que los modelos sigan cargados (/api/ps) y recargarlos si no
    # (p. ej. tras reiniciar Ollama). 0 = solo precalentar al arrancar.
    ai_warmup_check_seconds: int = 60
    # Límite de tokens del prompt. Sin definir = ai_num_ctx - ai_num_predict - margen.
    ai_prompt_max_tokens: int | None = None
    ai_prompt_margin_tokens: int = 256
    # Puntaje mínimo de una cita (relevancia devuelta por Chroma). Sin definir = no se filtra: el
    # valor se calibra con el feedback real de ai_citation_feedback (hoy [POR VERIFICAR]; el banco
    # de pruebas calcula uno sugerido). Un puntaje menor que el umbral no se devuelve.
    ai_citation_min_score: float | None = None
    # Puntaje minimo para sugerir un control al preclasificar evidencia global (D7). Sin definir =
    # se sugieren siempre los 3 mejores; el valor se calibra con el feedback real ([POR VERIFICAR]).
    ai_classify_min_score: float | None = None
    chroma_persist_dir: str = "/data/chroma"
    rag_docs_dir: str = "/data/knowledge-base"
    rag_chunk_size: int = 1000
    rag_chunk_overlap: int = 200
    rag_top_k: int = 5
    cors_origins: list[str] = ["*"]
    # Clave compartida que backend-core manda en el header X-Internal-Api-Key. Vacía (default)
    # = verificación deshabilitada, para no romper tests/desarrollo local; en docker-compose
    # siempre viene seteada con un valor real (ver AI_INTERNAL_API_KEY en .env).
    internal_api_key: str = ""
    # Entorno de esta instancia (APP_ENV): development | staging | production. Ver app/version.py.
    app_env: str = "development"

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    @field_validator(
        "ai_temperature",
        "ai_seed",
        "ai_num_thread",
        "ollama_num_parallel",
        "ai_prompt_max_tokens",
        "ai_citation_min_score",
        "ai_classify_min_score",
        mode="before",
    )
    @classmethod
    def _empty_is_unset(cls, value: object) -> object:
        # docker-compose pasa "${AI_TEMPERATURE:-}" como cadena vacía cuando no está definida.
        if isinstance(value, str) and not value.strip():
            return None
        return value

    @property
    def prompt_max_tokens(self) -> int:
        """Tope de tokens del prompt: lo que queda del contexto tras reservar la respuesta."""
        if self.ai_prompt_max_tokens is not None:
            return self.ai_prompt_max_tokens
        return max(1, self.ai_num_ctx - self.ai_num_predict - self.ai_prompt_margin_tokens)


@lru_cache
def get_settings() -> Settings:
    return Settings()
