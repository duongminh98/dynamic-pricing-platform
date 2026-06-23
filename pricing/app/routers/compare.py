from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from ..database import get_db
from pydantic import BaseModel
from typing import List

router = APIRouter(prefix="/pricing", tags=["pricing"])

class CompareRequest(BaseModel):
    line: str
    profile: dict
    product_ids: List[str]

@router.post("/quote/compare")
async def compare_quotes(request: CompareRequest, db: Session = Depends(get_db)):
    try:
        from ..pricing_engine.engine import quote
        results = []
        for pid in request.product_ids:
            res = quote(db, pid, request.profile)
            results.append({
                "product_id": pid,
                "quote": res
            })
            
        # Recommendation logic (simplified)
        if results:
            recommendation = min(results, key=lambda x: x["quote"]["final_premium_vnd"])
        else:
            recommendation = None
            
        db.commit()
        return {
            "quotes": results,
            "recommendation": recommendation["product_id"] if recommendation else None
        }
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=400, detail=str(e))
