"""Configuración cargada desde variables de entorno."""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    ollama_host: str = "http://ollama:11434"
    ollama_model: str = "llama3:8b"
    ollama_embedding_model: str = "nomic-embed-text"
    chroma_persist_dir: str = "/data/chroma"
    # Sin setear (default): modo local de siempre (PersistentClient, un solo proceso) -- así
    # queda docker-compose y los tests. Seteado (Kubernetes): backend-ai se conecta a un servidor
    # ChromaDB aparte por HTTP en vez de tocar el filesystem directamente, lo que le permite
    # correr con más de una réplica sin arriesgar corromper el índice. Ver
    # app/rag/chroma_client.py.
    chroma_server_host: str | None = None
    chroma_server_port: int = 8000
    rag_docs_dir: str = "/data/knowledge-base"
    rag_chunk_size: int = 1000
    rag_chunk_overlap: int = 200
    rag_top_k: int = 5
    cors_origins: list[str] = ["*"]
    # Clave compartida que backend-core manda en el header X-Internal-Api-Key. Vacía (default)
    # = verificación deshabilitada, para no romper tests/desarrollo local; en docker-compose
    # siempre viene seteada con un valor real (ver AI_INTERNAL_API_KEY en .env).
    internal_api_key: str = ""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


@lru_cache
def get_settings() -> Settings:
    return Settings()
