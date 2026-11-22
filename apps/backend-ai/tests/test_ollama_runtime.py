"""Tests de opciones por pedido, control de contexto, precalentamiento y endpoint de runtime."""

import asyncio
import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient
from langchain_core.documents import Document

from app.core.config import Settings
from app.main import app
from app.rag import ollama_runtime
from app.rag.ollama_runtime import PromptTooLargeError, chat_kwargs, embedding_kwargs
from app.rag.vector_store import VectorStore

# Se captura una vez: si un test parchea dos veces, la segunda no debe envolver a la primera.
_REAL_ASYNC_CLIENT = httpx.AsyncClient


@pytest.fixture(autouse=True)
def _clean_effective_context() -> Any:
    """El contexto efectivo es estado del módulo: cada test parte (y deja) en blanco."""
    ollama_runtime.reset_effective_context()
    yield
    ollama_runtime.reset_effective_context()


def _settings(**overrides: Any) -> Settings:
    return Settings(_env_file=None, **overrides)  # type: ignore[call-arg]


def _mock_ollama(
    monkeypatch: pytest.MonkeyPatch, handler: Callable[[httpx.Request], httpx.Response]
) -> None:
    """Hace que todo httpx.AsyncClient del módulo hable con un Ollama simulado."""
    real = _REAL_ASYNC_CLIENT

    def factory(*args: Any, **kwargs: Any) -> httpx.AsyncClient:
        kwargs["transport"] = httpx.MockTransport(handler)
        return real(*args, **kwargs)

    monkeypatch.setattr(ollama_runtime.httpx, "AsyncClient", factory)


# ----------------------------------------------------------------------
# Configuración y opciones
# ----------------------------------------------------------------------
def test_defaults_match_the_spec() -> None:
    s = _settings()
    assert s.ai_num_ctx == 16384
    assert s.ai_keep_alive == "-1"
    assert s.ai_think is False
    assert s.ai_warmup is True
    # Lo medido en el banco (perfil tuned_t12): temperatura 0,1 y 12 hilos; sin semilla fija.
    assert s.ai_temperature == 0.1 and s.ai_num_thread == 12 and s.ai_seed is None
    # Exactamente un LLM y un embedding (decisión 2026-11-22).
    assert s.ollama_model == "nemotron-3-nano:30b"
    assert s.ollama_embedding_model == "bge-m3"
    # Los umbrales se calibran después con datos reales de bge-m3: sin definir.
    assert s.ai_citation_min_score is None and s.ai_classify_min_score is None
    assert s.prompt_max_tokens == 16384 - s.ai_num_predict - s.ai_prompt_margin_tokens


def test_empty_optional_env_values_are_unset(monkeypatch: pytest.MonkeyPatch) -> None:
    # docker-compose pasa "${AI_TEMPERATURE:-}" como cadena vacía.
    monkeypatch.setenv("AI_TEMPERATURE", "")
    monkeypatch.setenv("AI_NUM_THREAD", "")
    monkeypatch.setenv("AI_PROMPT_MAX_TOKENS", "")
    s = _settings()
    assert s.ai_temperature is None
    assert s.ai_num_thread is None
    assert s.prompt_max_tokens > 0


def test_explicit_prompt_limit_wins() -> None:
    assert _settings(ai_prompt_max_tokens=1000).prompt_max_tokens == 1000


def test_chat_kwargs_only_sends_defined_optionals() -> None:
    base = chat_kwargs(_settings(ai_temperature=None, ai_num_thread=None))
    assert set(base) == {"num_ctx", "keep_alive", "num_predict"}
    assert base["keep_alive"] == -1 and isinstance(base["keep_alive"], int)

    full = chat_kwargs(_settings(ai_temperature=0.2, ai_seed=7, ai_num_thread=12))
    assert full["temperature"] == 0.2 and full["seed"] == 7 and full["num_thread"] == 12


def test_default_options_sent_to_the_llm_include_temperature_and_threads() -> None:
    sent = chat_kwargs(_settings())
    assert sent["temperature"] == 0.1 and sent["num_thread"] == 12 and "seed" not in sent


def test_keep_alive_duration_string_is_kept() -> None:
    assert chat_kwargs(_settings(ai_keep_alive="30m"))["keep_alive"] == "30m"


def test_reasoning_only_when_requested_and_supported() -> None:
    assert "reasoning" not in chat_kwargs(_settings(ai_think=True), think_supported=False)
    assert "reasoning" not in chat_kwargs(_settings(ai_think=False), think_supported=True)
    assert chat_kwargs(_settings(ai_think=True), think_supported=True)["reasoning"] is True


def test_embedding_uses_same_ctx_and_keep_alive_as_llm() -> None:
    s = _settings(ai_num_ctx=8192, ai_keep_alive="10m", ai_num_thread=4)
    emb, llm = embedding_kwargs(s), chat_kwargs(s)
    assert emb["num_ctx"] == llm["num_ctx"] == 8192
    assert emb["keep_alive"] == llm["keep_alive"] == "10m"
    assert emb["num_thread"] == 4


def test_vector_store_builds_llm_and_embeddings_with_options(tmp_path: Path) -> None:
    s = _settings(chroma_persist_dir=str(tmp_path), ai_num_ctx=4096, ai_num_predict=300, ai_seed=3)
    store = VectorStore(settings=s)
    assert store.llm.num_ctx == 4096
    assert store.llm.num_predict == 300
    assert store.llm.seed == 3
    assert store.llm.keep_alive == -1
    assert store.embeddings.num_ctx == 4096
    assert store.embeddings.keep_alive == -1


# ----------------------------------------------------------------------
# Control de contexto
# ----------------------------------------------------------------------
class SpyLLM:
    def __init__(self) -> None:
        self.calls = 0

    def invoke(self, prompt: str) -> object:
        self.calls += 1
        return type("Response", (), {"content": "ok"})()


class OneDocRetriever:
    def similarity_search(self, question: str, k: int = 5) -> list[Document]:
        return [Document(page_content="x" * 500, metadata={})]


def test_answer_rejects_oversized_prompt_without_calling_ollama(tmp_path: Path) -> None:
    s = _settings(chroma_persist_dir=str(tmp_path), ai_prompt_max_tokens=100)
    store = VectorStore(settings=s)
    store._vectorstore = OneDocRetriever()
    spy = SpyLLM()
    store.llm = spy
    with pytest.raises(PromptTooLargeError) as err:
        asyncio.run(store.answer("pregunta"))
    assert err.value.estimated_tokens > err.value.limit_tokens == 100
    assert spy.calls == 0


def test_remediation_rejects_oversized_prompt_without_calling_ollama(tmp_path: Path) -> None:
    s = _settings(chroma_persist_dir=str(tmp_path), ai_prompt_max_tokens=50)
    store = VectorStore(settings=s)
    store._vectorstore = OneDocRetriever()
    spy = SpyLLM()
    store.llm = spy
    with pytest.raises(PromptTooLargeError):
        asyncio.run(store.suggest_remediation("PR.AA-01", "desc", None, None, None, 1, 3))
    assert spy.calls == 0


def test_prompt_that_fits_is_sent(tmp_path: Path) -> None:
    store = VectorStore(settings=_settings(chroma_persist_dir=str(tmp_path)))
    store._vectorstore = OneDocRetriever()
    spy = SpyLLM()
    store.llm = spy
    result = asyncio.run(store.answer("pregunta"))
    assert result["answer"] == "ok" and spy.calls == 1


def test_api_maps_prompt_too_large_to_413() -> None:
    class Boom:
        async def suggest_remediation(self, **kwargs: object) -> dict:
            raise PromptTooLargeError(5000, 4000)

    app.state.vector_store = Boom()
    response = TestClient(app).post(
        "/api/v1/rag/remediation-tips",
        json={
            "control_code": "PR.AA-01",
            "control_description": "d",
            "current_level": 1,
            "target_level": 2,
        },
    )
    assert response.status_code == 413
    body = response.json()
    assert body["code"] == "PROMPT_TOO_LARGE"
    assert body["estimated_tokens"] == 5000 and body["limit_tokens"] == 4000


# ----------------------------------------------------------------------
# Runtime y precalentamiento
# ----------------------------------------------------------------------
def _ollama_handler(requests: list[httpx.Request]) -> Callable[[httpx.Request], httpx.Response]:
    def handler(request: httpx.Request) -> httpx.Response:
        requests.append(request)
        path = request.url.path
        if path == "/api/version":
            return httpx.Response(200, json={"version": "0.40.6"})
        if path == "/api/ps":
            return httpx.Response(
                200,
                json={
                    "models": [
                        {
                            "name": "nemotron-3-nano:30b",
                            "size": 6_000,
                            "size_vram": 0,
                            "context_length": 16384,
                            "expires_at": "2318-01-01T00:00:00Z",
                        }
                    ]
                },
            )
        if path == "/api/tags":
            return httpx.Response(
                200,
                json={
                    "models": [
                        {"name": "nemotron-3-nano:30b", "digest": "aaa"},
                        {"name": "bge-m3:latest", "digest": "bbb"},
                    ]
                },
            )
        if path == "/api/show":
            return httpx.Response(200, json={"capabilities": ["completion"]})
        return httpx.Response(200, json={})

    return handler


def test_runtime_endpoint_reports_effective_options(monkeypatch: pytest.MonkeyPatch) -> None:
    _mock_ollama(monkeypatch, _ollama_handler([]))
    app.state.warmup = {"status": "ready"}
    response = TestClient(app).get("/api/v1/runtime")
    assert response.status_code == 200
    body = response.json()
    assert body["ollama"]["reachable"] is True and body["ollama"]["version"] == "0.40.6"
    assert body["options"]["num_ctx"] == 16384
    assert body["options"]["keep_alive"] == -1
    assert body["models"]["llm"]["digest"] == "aaa" and body["models"]["llm"]["loaded"] is True
    emb = body["models"]["embedding"]
    assert emb["digest"] == "bbb" and emb["loaded"] is False
    assert body["warmup"]["status"] == "ready"


def test_runtime_endpoint_survives_ollama_down(monkeypatch: pytest.MonkeyPatch) -> None:
    def down(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("sin conexión", request=request)

    _mock_ollama(monkeypatch, down)
    body = TestClient(app).get("/api/v1/runtime").json()
    assert body["ollama"]["reachable"] is False
    assert body["options"]["num_ctx"] == 16384


def test_warmup_loads_both_models_with_the_app_options(monkeypatch: pytest.MonkeyPatch) -> None:
    requests: list[httpx.Request] = []
    _mock_ollama(monkeypatch, _ollama_handler(requests))
    state: dict[str, Any] = {}
    asyncio.run(ollama_runtime.warmup(_settings(ai_num_thread=8), state))

    assert state["status"] == "ready"
    by_path = {r.url.path: json.loads(r.content) for r in requests}
    assert by_path["/api/generate"]["prompt"] == ""
    assert by_path["/api/generate"]["model"] == "nemotron-3-nano:30b"
    assert by_path["/api/embed"]["model"] == "bge-m3"
    for payload in (by_path["/api/generate"], by_path["/api/embed"]):
        assert payload["keep_alive"] == -1
        assert payload["options"] == {"num_ctx": 16384, "num_thread": 8}


def test_warmup_retries_while_ollama_is_not_ready(monkeypatch: pytest.MonkeyPatch) -> None:
    attempts = {"n": 0}

    def flaky(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/generate":
            attempts["n"] += 1
            if attempts["n"] < 3:
                return httpx.Response(503, json={"error": "cargando"})
        return httpx.Response(200, json={})

    async def no_sleep(_seconds: float) -> None:
        return None

    _mock_ollama(monkeypatch, flaky)
    monkeypatch.setattr(ollama_runtime.asyncio, "sleep", no_sleep)
    state: dict[str, Any] = {}
    asyncio.run(ollama_runtime.warmup(_settings(), state))
    assert state["status"] == "ready" and state["attempts"] == 3


class MetricsLLM:
    def invoke(self, prompt: str) -> object:
        return type(
            "Response",
            (),
            {
                "content": "Resumen.\n- Paso uno\n- Paso dos",
                "response_metadata": {"eval_count": 42, "prompt_eval_count": 100, "x": 1},
            },
        )()


def test_remediation_reports_generated_model_and_ollama_metrics(tmp_path: Path) -> None:
    store = VectorStore(settings=_settings(chroma_persist_dir=str(tmp_path)))
    store._vectorstore = OneDocRetriever()
    store.llm = MetricsLLM()
    result = asyncio.run(store.suggest_remediation("PR.AA-01", "desc", None, None, None, 1, 3))
    assert result["generated"] is True
    assert result["model"] == "nemotron-3-nano:30b"
    assert result["metrics"]["eval_count"] == 42
    assert result["metrics"]["prompt_eval_count"] == 100
    assert "x" not in result["metrics"]
    assert result["metrics"]["prompt_tokens_estimated"] > 0


def test_remediation_marks_fallback_as_not_generated(tmp_path: Path) -> None:
    class Down:
        def invoke(self, prompt: str) -> object:
            raise RuntimeError("ollama caído")

    store = VectorStore(settings=_settings(chroma_persist_dir=str(tmp_path)))
    store._vectorstore = OneDocRetriever()
    store.llm = Down()
    result = asyncio.run(store.suggest_remediation("PR.AA-01", "desc", None, None, None, 1, 3))
    assert result["generated"] is False


def test_models_loaded_reports_whether_both_models_are_in_memory(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _mock_ollama(monkeypatch, _ollama_handler([]))  # solo nemotron-3-nano:30b cargado
    assert asyncio.run(ollama_runtime.models_loaded(_settings())) is False

    def both(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, json={"models": [{"name": "nemotron-3-nano:30b"}, {"name": "bge-m3:latest"}]}
        )

    _mock_ollama(monkeypatch, both)
    assert asyncio.run(ollama_runtime.models_loaded(_settings())) is True


def test_models_loaded_is_none_when_ollama_does_not_answer(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def down(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("sin conexión", request=request)

    _mock_ollama(monkeypatch, down)
    assert asyncio.run(ollama_runtime.models_loaded(_settings())) is None


def test_keep_warm_reloads_the_models_after_ollama_restarts(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    generates = {"n": 0}

    class StopLoopError(Exception):
        pass

    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/generate":
            generates["n"] += 1
        if path == "/api/ps":
            # Ollama reinició: no tiene nada cargado hasta que se vuelve a precalentar.
            both = [{"name": "nemotron-3-nano:30b"}, {"name": "bge-m3:latest"}]
            return httpx.Response(200, json={"models": both if generates["n"] >= 2 else []})
        return httpx.Response(200, json={})

    sleeps = {"n": 0}

    async def fake_sleep(_seconds: float) -> None:
        sleeps["n"] += 1
        if sleeps["n"] > 3:
            raise StopLoopError

    _mock_ollama(monkeypatch, handler)
    monkeypatch.setattr(ollama_runtime.asyncio, "sleep", fake_sleep)
    state: dict[str, Any] = {}
    with pytest.raises(StopLoopError):
        asyncio.run(ollama_runtime.keep_warm(_settings(ai_warmup_check_seconds=60), state))

    assert generates["n"] == 2, "precalentó al arrancar y otra vez al detectar la descarga"
    assert state["reloads"] == 1
    assert state["status"] == "ready"


def test_keep_warm_without_watchdog_only_warms_up_once(monkeypatch: pytest.MonkeyPatch) -> None:
    requests: list[httpx.Request] = []
    _mock_ollama(monkeypatch, _ollama_handler(requests))
    state: dict[str, Any] = {}
    asyncio.run(ollama_runtime.keep_warm(_settings(ai_warmup_check_seconds=0), state))

    assert state["status"] == "ready"
    paths = [r.url.path for r in requests]
    assert paths.count("/api/generate") == 1
    assert paths.count("/api/embed") == 1


# ----------------------------------------------------------------------
# Contexto efectivo: un modelo que carga con menos contexto que el pedido (mecanismo general;
# las cifras son de prueba, no las reales de nemotron)
# ----------------------------------------------------------------------
def _ctx_handler(loaded: bool = True) -> Callable[[httpx.Request], httpx.Response]:
    def handler(request: httpx.Request) -> httpx.Response:
        path = request.url.path
        if path == "/api/ps":
            models = (
                [
                    {"name": "nemotron-3-nano:30b", "context_length": 8192},
                    {"name": "bge-m3:latest", "context_length": 2048},
                ]
                if loaded
                else []
            )
            return httpx.Response(200, json={"models": models})
        if path == "/api/show":
            body = json.loads(request.content)
            ctx = 8192 if body["model"].startswith("nemotron") else 2048
            arch = "nemotron_h" if body["model"].startswith("nemotron") else "bert"
            return httpx.Response(200, json={"model_info": {f"{arch}.context_length": ctx}})
        if path == "/api/version":
            return httpx.Response(200, json={"version": "0.40.6"})
        if path == "/api/tags":
            return httpx.Response(200, json={"models": []})
        return httpx.Response(200, json={})

    return handler


def test_prompt_limit_without_model_data_uses_the_requested_context() -> None:
    s = _settings()
    assert ollama_runtime.prompt_limit(s) == 16384 - 1024 - 256


def test_prompt_limit_uses_the_effective_context_of_the_model(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _mock_ollama(monkeypatch, _ctx_handler())
    s = _settings()
    asyncio.run(ollama_runtime.refresh_effective_context(s))

    assert ollama_runtime.get_effective_context("llm") == 8192
    assert ollama_runtime.get_effective_context("embedding") == 2048
    assert ollama_runtime.llm_context(s) == 8192
    assert ollama_runtime.prompt_limit(s) == 8192 - 1024 - 256


def test_a_prompt_that_only_fit_the_requested_context_is_now_rejected(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    _mock_ollama(monkeypatch, _ctx_handler())
    s = _settings(chroma_persist_dir=str(tmp_path))
    asyncio.run(ollama_runtime.refresh_effective_context(s))
    nine_thousand_tokens = "x" * (9000 * 3)

    with pytest.raises(PromptTooLargeError) as err:
        ollama_runtime.guard_prompt(s, nine_thousand_tokens)
    assert err.value.limit_tokens == 8192 - 1024 - 256

    # Con lo pedido (16384) el mismo prompt habría pasado y Ollama lo habría recortado.
    ollama_runtime.reset_effective_context()
    assert ollama_runtime.guard_prompt(s, nine_thousand_tokens) == 9000


def test_effective_context_falls_back_to_the_model_maximum_when_not_loaded(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _mock_ollama(monkeypatch, _ctx_handler(loaded=False))
    asyncio.run(ollama_runtime.refresh_effective_context(_settings()))

    assert ollama_runtime.get_effective_context("llm") == 8192
    assert ollama_runtime.get_effective_context("embedding") == 2048


def test_a_model_with_more_context_than_requested_does_not_raise_the_limit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _mock_ollama(monkeypatch, _ctx_handler())
    s = _settings(ai_num_ctx=4096)
    asyncio.run(ollama_runtime.refresh_effective_context(s))

    assert ollama_runtime.llm_context(s) == 4096


def test_explicit_prompt_limit_can_shrink_but_never_exceed_the_effective_context(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _mock_ollama(monkeypatch, _ctx_handler())
    asyncio.run(ollama_runtime.refresh_effective_context(_settings()))

    assert ollama_runtime.prompt_limit(_settings(ai_prompt_max_tokens=300)) == 300
    assert ollama_runtime.prompt_limit(_settings(ai_prompt_max_tokens=15000)) == 8192 - 1024 - 256


def test_embedding_input_limit_follows_the_effective_embedding_context(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    assert ollama_runtime.embedding_input_limit(_settings()) is None
    _mock_ollama(monkeypatch, _ctx_handler())
    asyncio.run(ollama_runtime.refresh_effective_context(_settings()))

    assert ollama_runtime.embedding_input_limit(_settings()) == 2048 - 256


def test_runtime_endpoint_reports_the_requested_vs_effective_context(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _mock_ollama(monkeypatch, _ctx_handler())
    app.state.warmup = {"status": "ready"}

    body = TestClient(app).get("/api/v1/runtime").json()

    ctx = body["context"]
    assert ctx["requested_num_ctx"] == 16384
    assert ctx["llm"] == {"effective": 8192, "source": "ps", "used_for_limit": 8192}
    assert ctx["embedding"]["effective"] == 2048
    assert ctx["embedding"]["input_max_tokens"] == 2048 - 256
    assert ctx["prompt_max_tokens"] == 8192 - 1024 - 256
    assert ctx["mismatch"] is True
    assert body["options"]["prompt_max_tokens"] == 8192 - 1024 - 256


def test_runtime_endpoint_with_ollama_down_reports_no_effective_context(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def down(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("sin conexión", request=request)

    _mock_ollama(monkeypatch, down)
    ctx = TestClient(app).get("/api/v1/runtime").json()["context"]

    assert ctx["llm"]["effective"] is None
    assert ctx["mismatch"] is False
    assert ctx["prompt_max_tokens"] == 16384 - 1024 - 256


def test_chunk_size_larger_than_the_embedding_context_is_warned(
    monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
) -> None:
    _mock_ollama(monkeypatch, _ctx_handler())
    with caplog.at_level("WARNING"):
        asyncio.run(ollama_runtime.refresh_effective_context(_settings(rag_chunk_size=9000)))

    assert "RAG_CHUNK_SIZE=9000" in caplog.text
    assert "carga con contexto 8192, menor que AI_NUM_CTX=16384" in caplog.text


def test_keep_warm_refreshes_the_effective_context(monkeypatch: pytest.MonkeyPatch) -> None:
    _mock_ollama(monkeypatch, _ctx_handler())
    asyncio.run(ollama_runtime.keep_warm(_settings(ai_warmup_check_seconds=0), {}))

    assert ollama_runtime.get_effective_context("llm") == 8192


def test_models_endpoint_reports_the_configured_models_without_asking_ollama() -> None:
    # backend-core lo usa para detectar un cambio de modelo de embedding y reindexar.
    response = TestClient(app).get("/api/v1/runtime/models")
    assert response.status_code == 200
    assert response.json() == {"llm": "nemotron-3-nano:30b", "embedding": "bge-m3"}
