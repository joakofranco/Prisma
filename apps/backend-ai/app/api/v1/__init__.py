from fastapi import APIRouter

from app.api.v1 import evidence, rag

router = APIRouter()


@router.get("/ping", tags=["Health"])
async def ping() -> dict[str, bool]:
    return {"ok": True}


router.include_router(rag.rag_router)
router.include_router(rag.evaluate_router)
router.include_router(evidence.evidence_router)
