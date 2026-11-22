"""Endpoints de ingesta y busqueda de citas sobre evidencias, aislados por organizacion.

Solo backend-core deberia llamar a estos endpoints (ver app.core.security.verify_internal_key,
aplicado a todo el router): es quien ya resolvio y valido a que organizacion pertenece el
request antes de llegar acá.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app.api.v1.rag import run_until_disconnect
from app.core.config import get_settings
from app.core.security import verify_internal_key
from app.rag.evidence_store import EvidenceStore

evidence_router = APIRouter(
    prefix="/evidence", tags=["Evidence RAG"], dependencies=[Depends(verify_internal_key)]
)


class EvidenceIngestRequest(BaseModel):
    organization_id: str = Field(min_length=1)
    evaluation_id: str = Field(min_length=1)
    evidence_id: str = Field(min_length=1)
    control_id: str | None = None
    file_name: str = Field(min_length=1)
    file_url: str = Field(min_length=1)


class EvidenceIngestResponse(BaseModel):
    indexed: bool
    chunks: int


class EvidenceCitation(BaseModel):
    # Id estable del fragmento (evidencia + posicion): permite conservar la decision del auditor.
    fragment_id: str | None = None
    # True = viene de evidencia sin control asignado (global): se muestra como tal.
    unassigned: bool = False
    evidence_id: str | None
    file_name: str | None
    location: str | None
    snippet: str
    score: float


class EvidenceCitationsRequest(BaseModel):
    organization_id: str = Field(min_length=1)
    evaluation_id: str = Field(min_length=1)
    control_id: str | None = None
    query: str = Field(min_length=1, max_length=4000)
    top_k: int | None = Field(default=None, ge=1, le=20)


class EvidenceCitationsResponse(BaseModel):
    citations: list[EvidenceCitation]


class ClassifyCandidate(BaseModel):
    control_id: str = Field(min_length=1)
    query: str = Field(min_length=1, max_length=4000)


class EvidenceClassifyRequest(BaseModel):
    organization_id: str = Field(min_length=1)
    evaluation_id: str = Field(min_length=1)
    evidence_id: str = Field(min_length=1)
    candidates: list[ClassifyCandidate] = Field(min_length=1, max_length=1500)
    top_n: int = Field(default=3, ge=1, le=10)


class ClassifySuggestion(BaseModel):
    control_id: str
    score: float
    fragment_id: str | None = None


class EvidenceClassifyResponse(BaseModel):
    suggestions: list[ClassifySuggestion]
    # False = AI_CLASSIFY_MIN_SCORE sin definir: los puntajes no tienen un umbral calibrado con
    # datos reales y las sugerencias son «sin confianza calibrada» (3.4).
    calibrated: bool = False


def _store(request: Request) -> EvidenceStore:
    store: EvidenceStore | None = getattr(request.app.state, "evidence_store", None)
    if store is None:
        store = EvidenceStore()
        request.app.state.evidence_store = store
    return store


@evidence_router.post("/ingest", response_model=EvidenceIngestResponse)
async def ingest_evidence(request: Request, body: EvidenceIngestRequest) -> EvidenceIngestResponse:
    chunks = await _store(request).ingest(
        organization_id=body.organization_id,
        evaluation_id=body.evaluation_id,
        evidence_id=body.evidence_id,
        control_id=body.control_id,
        file_name=body.file_name,
        file_url=body.file_url,
    )
    return EvidenceIngestResponse(indexed=chunks > 0, chunks=chunks)


@evidence_router.post("/citations", response_model=EvidenceCitationsResponse)
async def evidence_citations(
    request: Request, body: EvidenceCitationsRequest
) -> EvidenceCitationsResponse:
    results = await _store(request).citations(
        organization_id=body.organization_id,
        evaluation_id=body.evaluation_id,
        control_id=body.control_id,
        query=body.query,
        top_k=body.top_k,
    )
    return EvidenceCitationsResponse(citations=[EvidenceCitation(**r) for r in results])


@evidence_router.post("/classify", response_model=EvidenceClassifyResponse)
async def classify_evidence(
    request: Request, body: EvidenceClassifyRequest
) -> EvidenceClassifyResponse | JSONResponse:
    """Sugiere a que controles podria pertenecer una evidencia sin control asignado (D7)."""
    # Si backend-core corta por timeout, el cálculo se cancela (antes seguía y saturaba la CPU).
    results = await run_until_disconnect(
        request,
        _store(request).classify(
            organization_id=body.organization_id,
            evaluation_id=body.evaluation_id,
            evidence_id=body.evidence_id,
            candidates=[c.model_dump() for c in body.candidates],
            top_n=body.top_n,
        ),
    )
    if isinstance(results, JSONResponse):
        return results
    return EvidenceClassifyResponse(
        suggestions=[ClassifySuggestion(**r) for r in results],
        calibrated=get_settings().ai_classify_min_score is not None,
    )


@evidence_router.delete("/{organization_id}/{evidence_id}")
async def delete_evidence(
    request: Request, organization_id: str, evidence_id: str
) -> dict[str, bool]:
    _store(request).delete_evidence(organization_id, evidence_id)
    return {"deleted": True}
