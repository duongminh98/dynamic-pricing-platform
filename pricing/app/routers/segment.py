from fastapi import APIRouter
from pydantic import BaseModel

router = APIRouter(prefix="/pricing", tags=["pricing"])

class SegmentRequest(BaseModel):
    product_id: str
    profile: dict

@router.post("/segment")
async def calculate_segment(request: SegmentRequest):
    from ..pricing_engine.segment import get_risk_segment
    segment = get_risk_segment(request.product_id, request.profile)
    return {"risk_segment": segment}
