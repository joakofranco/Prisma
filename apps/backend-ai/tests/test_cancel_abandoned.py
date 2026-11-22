"""Cancelación de la generación abandonada: si backend-core corta la conexión, backend-ai aborta
el pedido al LLM (y con él, Ollama) en vez de seguir generando una respuesta que nadie lee."""

import asyncio
import socket
import threading
import time
from pathlib import Path
from typing import Any

import httpx
import pytest
import uvicorn
from langchain_core.documents import Document

from app.api.v1 import rag as rag_api
from app.core.config import Settings
from app.main import app
from app.rag.vector_store import VectorStore


class FakeRequest:
    """Request de Starlette reducido a lo que usa run_until_disconnect."""

    def __init__(self, disconnect_after_polls: int | None) -> None:
        self.polls = 0
        self.after = disconnect_after_polls

    async def is_disconnected(self) -> bool:
        self.polls += 1
        return self.after is not None and self.polls > self.after


@pytest.fixture(autouse=True)
def _fast_polling(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(rag_api, "DISCONNECT_POLL_SECONDS", 0.01)


async def _slow(state: dict[str, Any], seconds: float = 30.0) -> str:
    try:
        await asyncio.sleep(seconds)
        return "terminó"
    except asyncio.CancelledError:
        state["cancelled"] = True
        raise


def test_the_work_is_cancelled_when_the_client_disconnects() -> None:
    state: dict[str, Any] = {}

    async def scenario() -> Any:
        return await rag_api.run_until_disconnect(FakeRequest(2), _slow(state))  # type: ignore[arg-type]

    result = asyncio.run(scenario())

    assert state["cancelled"] is True
    assert result.status_code == rag_api.CLIENT_CLOSED_REQUEST


def test_the_result_is_returned_when_the_client_stays_connected() -> None:
    state: dict[str, Any] = {}

    async def scenario() -> Any:
        return await rag_api.run_until_disconnect(FakeRequest(None), _slow(state, 0.05))  # type: ignore[arg-type]

    assert asyncio.run(scenario()) == "terminó"
    assert "cancelled" not in state


def test_an_external_cancellation_is_not_swallowed() -> None:
    async def scenario() -> None:
        task = asyncio.ensure_future(
            rag_api.run_until_disconnect(FakeRequest(None), _slow({}))  # type: ignore[arg-type]
        )
        await asyncio.sleep(0.05)
        task.cancel()
        await task

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(scenario())


def test_the_watcher_stops_when_the_work_finishes() -> None:
    request = FakeRequest(None)

    async def scenario() -> None:
        await rag_api.run_until_disconnect(request, _slow({}, 0.05))  # type: ignore[arg-type]
        polls = request.polls
        await asyncio.sleep(0.1)
        assert request.polls == polls, "el vigilante sigue consultando después de terminar"

    asyncio.run(scenario())


# ----------------------------------------------------------------------
# VectorStore: llamada cancelable al LLM
# ----------------------------------------------------------------------
def _store(tmp_path: Path, llm: Any) -> VectorStore:
    settings = Settings(_env_file=None, chroma_persist_dir=str(tmp_path))  # type: ignore[call-arg]
    store = VectorStore(settings=settings)

    class Retriever:
        def similarity_search(self, question: str, k: int = 5) -> list[Document]:
            return [Document(page_content="ctx", metadata={})]

    store._vectorstore = Retriever()
    store.llm = llm
    return store


class AsyncLLM:
    def __init__(self) -> None:
        self.started = asyncio.Event()
        self.cancelled = False

    async def ainvoke(self, prompt: str) -> object:
        self.started.set()
        try:
            await asyncio.sleep(30)
        except asyncio.CancelledError:
            self.cancelled = True
            raise
        return type("R", (), {"content": "x"})()


def test_cancelling_the_task_cancels_the_llm_call_and_is_not_swallowed(tmp_path: Path) -> None:
    llm = AsyncLLM()
    store = _store(tmp_path, llm)

    async def scenario() -> None:
        task = asyncio.ensure_future(
            store.suggest_remediation("PR.AA-01", "desc", None, None, None, 1, 3)
        )
        await llm.started.wait()
        task.cancel()
        await task

    with pytest.raises(asyncio.CancelledError):
        asyncio.run(scenario())
    assert llm.cancelled is True


def test_an_llm_without_ainvoke_still_works_through_a_thread(tmp_path: Path) -> None:
    class SyncLLM:
        def invoke(self, prompt: str) -> object:
            return type("R", (), {"content": "Resumen\n- Paso"})()

    result = asyncio.run(
        _store(tmp_path, SyncLLM()).suggest_remediation("PR.AA-01", "desc", None, None, None, 1, 3)
    )
    assert result["generated"] is True
    assert result["tips"] == ["Paso"]


# ----------------------------------------------------------------------
# De punta a punta con un servidor real: el corte de la conexión llega a la tarea del LLM
# ----------------------------------------------------------------------
def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def test_a_real_client_disconnect_aborts_the_llm_call(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    cancelled = threading.Event()
    started = threading.Event()

    class SlowLLM:
        async def ainvoke(self, prompt: str) -> object:
            started.set()
            try:
                await asyncio.sleep(60)
            except asyncio.CancelledError:
                cancelled.set()
                raise
            return type("R", (), {"content": "x"})()

    app.state.vector_store = _store(tmp_path, SlowLLM())
    port = _free_port()
    server = uvicorn.Server(
        uvicorn.Config(app, host="127.0.0.1", port=port, lifespan="off", log_level="error")
    )
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    try:
        deadline = time.time() + 10
        while not server.started and time.time() < deadline:
            time.sleep(0.05)
        assert server.started, "el servidor de prueba no arrancó"

        body = {
            "control_code": "PR.AA-01",
            "control_description": "desc",
            "current_level": 0,
            "target_level": 2,
        }
        # backend-core "corta por timeout": el cliente se rinde a los 0,5 s.
        with pytest.raises(httpx.ReadTimeout):
            httpx.post(
                f"http://127.0.0.1:{port}/api/v1/rag/remediation-tips", json=body, timeout=0.5
            )

        assert started.is_set()
        assert cancelled.wait(5), "backend-ai siguió esperando al LLM tras la desconexión"
    finally:
        server.should_exit = True
        thread.join(timeout=10)
