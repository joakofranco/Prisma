"""Tests de los endpoints RAG (query, ingest, status, evaluate/evidence)."""

import pytest
from fastapi.testclient import TestClient

from app.main import app


class FakeVectorStore:
    def __init__(self) -> None:
        self.status_data = {"ready": True, "collection": "mcu-5.0", "documents": 3}

    def status(self) -> dict:
        return self.status_data

    async def answer(self, question: str, k: int | None = None) -> dict:
        return {
            "answer": "La respuesta generada por el RAG",
            "sources": [{"source": "mcu-5.0/control.md", "page": 1, "content": "texto"}],
        }

    async def ingest(self, text: str, source: str | None = None) -> int:
        return 2

    async def suggest_remediation(self, **kwargs: object) -> dict:
        return {
            "summary": "Definir una política de contraseñas formal.",
            "tips": ["Redactar la política", "Configurarla en el IdP", "Comunicarla al personal"],
        }


@pytest.fixture
def client() -> TestClient:
    app.state.vector_store = FakeVectorStore()
    return TestClient(app)


def test_rag_status(client: TestClient) -> None:
    response = client.get("/api/v1/rag/status")
    assert response.status_code == 200
    body = response.json()
    assert body["ready"] is True
    assert body["collection"] == "mcu-5.0"
    assert body["documents"] == 3


def test_rag_query(client: TestClient) -> None:
    response = client.post("/api/v1/rag/query", json={"question": "¿Qué es el MCU 5.0?"})
    assert response.status_code == 200
    body = response.json()
    assert body["answer"] == "La respuesta generada por el RAG"
    assert body["sources"][0]["source"] == "mcu-5.0/control.md"


def test_rag_query_rejects_empty_question(client: TestClient) -> None:
    response = client.post("/api/v1/rag/query", json={"question": ""})
    assert response.status_code == 422


def test_rag_ingest(client: TestClient) -> None:
    response = client.post("/api/v1/rag/ingest", json={"text": "Contenido del documento"})
    assert response.status_code == 200
    assert response.json()["documents"] == 3


def test_remediation_tips(client: TestClient) -> None:
    response = client.post(
        "/api/v1/rag/remediation-tips",
        json={
            "control_code": "PR.AC-1",
            "control_description": "La organización cuenta con una política de contraseñas",
            "function_name": "Proteger",
            "category_name": "Control de Acceso",
            "subcategory_name": "Gestión de identidades",
            "current_level": 0,
            "target_level": 1,
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["summary"] == "Definir una política de contraseñas formal."
    assert len(body["tips"]) == 3


def test_remediation_tips_rejects_invalid_level(client: TestClient) -> None:
    response = client.post(
        "/api/v1/rag/remediation-tips",
        json={
            "control_code": "PR.AC-1",
            "control_description": "desc",
            "current_level": 0,
            "target_level": 9,
        },
    )
    assert response.status_code == 422


def test_evaluate_evidence(client: TestClient) -> None:
    response = client.post(
        "/api/v1/evaluate/evidence",
        json={
            "control_id": "C-001",
            "control_description": "Existe una política de seguridad",
            "evidence_text": "PDF 40 páginas con la política vigente",
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["conclusion"] in {"evidencia suficiente", "evidencia insuficiente"}
    assert body["rationale"] == "La respuesta generada por el RAG"
    assert body["sources"]


def test_rag_status_returns_503_without_store() -> None:
    app.state.vector_store = None
    client = TestClient(app)
    response = client.get("/api/v1/rag/status")
    assert response.status_code == 503
