"""Traza del modo investigación (C8): la semilla viaja al LLM y la traza solo se arma si se pide."""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any

import pytest
from langchain_core.documents import Document

from app.core.config import Settings
from app.rag import ollama_runtime
from app.rag.vector_store import VectorStore

_REAL_TRACE_CONFIG = ollama_runtime.trace_config


def _settings(**kw: Any) -> Settings:
    base: dict[str, Any] = {
        "ollama_model": "llama3:8b",
        "ollama_embedding_model": "nomic-embed-text",
        "internal_api_key": "",
    }
    base.update(kw)
    return Settings(**base)


class Retriever:
    def similarity_search(self, question: str, k: int = 5) -> list[Document]:
        return [Document(page_content="texto", metadata={"source": "mcu.pdf"})]

    def similarity_search_with_score(
        self, question: str, k: int = 5
    ) -> list[tuple[Document, float]]:
        return [(Document(page_content="texto", metadata={"source": "mcu.pdf"}), 0.42)]


class SeedLLM:
    """Registra la semilla de la copia con la que se invocó."""

    seen: list[Any] = []

    def __init__(self, seed: int | None = None) -> None:
        self.seed = seed

    def model_copy(self, update: dict[str, Any]) -> SeedLLM:
        return SeedLLM(update.get("seed"))

    def invoke(self, prompt: str) -> object:
        SeedLLM.seen.append(self.seed)
        return type(
            "R",
            (),
            {
                "content": "Resumen.\n- Paso uno",
                "response_metadata": {"eval_count": 7},
                "additional_kwargs": {"reasoning_content": "pienso"},
            },
        )()


@pytest.fixture(autouse=True)
def _config(monkeypatch: pytest.MonkeyPatch) -> None:
    SeedLLM.seen = []

    async def fake(settings: Settings) -> dict[str, Any]:
        return {"model": "llama3:8b", "model_digest": "abc", "num_ctx": 16384}

    monkeypatch.setattr(ollama_runtime, "trace_config", fake)


def _store(tmp_path: Path) -> VectorStore:
    store = VectorStore(settings=_settings(chroma_persist_dir=str(tmp_path)))
    store._vectorstore = Retriever()
    store.llm = SeedLLM()
    return store


def test_sin_trace_no_arma_la_traza_ni_envia_semilla(tmp_path: Path) -> None:
    result = asyncio.run(
        _store(tmp_path).suggest_remediation("PR.AA-01", "d", None, None, None, 1, 3, seed=5)
    )
    assert "trace" not in result
    assert SeedLLM.seen == [None]


def test_con_trace_guarda_semilla_prompt_respuesta_y_recuperacion(tmp_path: Path) -> None:
    result = asyncio.run(
        _store(tmp_path).suggest_remediation(
            "PR.AA-01", "d", None, None, None, 1, 3, trace=True, seed=1234
        )
    )
    assert SeedLLM.seen == [1234]
    trace = result["trace"]
    assert trace["seed"] == 1234
    assert trace["config"]["model_digest"] == "abc"
    assert "PR.AA-01" in trace["prompt"]
    assert trace["response"].startswith("Resumen.")
    assert trace["thinking"] == "pienso"
    assert trace["retrieval"] == [{"source": "mcu.pdf", "id": None, "score": 0.42}]
    assert trace["metrics"]["eval_count"] == 7


def test_trace_config_se_cachea_y_no_lanza(monkeypatch: pytest.MonkeyPatch) -> None:
    calls = {"n": 0}

    async def info(settings: Settings, warmup: Any = None) -> dict[str, Any]:
        calls["n"] += 1
        return {
            "options": {"temperature": 0.1, "keep_alive": -1},
            "context": {"llm": {"used_for_limit": 8192}},
            "ollama": {"reachable": True, "version": "0.9.0"},
            "models": {
                "llm": {"name": "m", "digest": "d1"},
                "embedding": {"name": "e", "digest": "d2"},
            },
        }

    monkeypatch.setattr(ollama_runtime, "runtime_info", info)
    ollama_runtime._trace_config_cache.update(at=0.0, value=None)
    s = _settings(ollama_num_parallel=2)
    first = asyncio.run(_REAL_TRACE_CONFIG(s))
    second = asyncio.run(_REAL_TRACE_CONFIG(s))
    assert first == second and calls["n"] == 1
    assert first["model_digest"] == "d1" and first["ollama_version"] == "0.9.0"
    assert first["num_ctx"] == 8192 and first["num_parallel"] == 2 and first["think"] is False
    ollama_runtime._trace_config_cache.update(at=0.0, value=None)
