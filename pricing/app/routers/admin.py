from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from ..database import get_db, ModelVersion, ChampionAssignment, AuditTrail
from pydantic import BaseModel
import datetime
import uuid

router = APIRouter(prefix="/admin", tags=["admin"])


class PromoteRequest(BaseModel):
    line: str
    model_version_id: str


class RollbackRequest(BaseModel):
    line: str


@router.get("/pricing/models")
async def list_models(db: Session = Depends(get_db)):
    return db.query(ModelVersion).all()


@router.post("/champion/promote")
async def promote_champion(request: PromoteRequest, db: Session = Depends(get_db)):
    from ..pricing_engine import governance
    try:
        result = governance.promote_champion(db, request.line, request.model_version_id)
        return {"status": "success", "promoted": result["promoted"], "champion": result.get("champion")}
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=400, detail=str(e))


@router.post("/champion/rollback")
async def rollback_champion(request: RollbackRequest, db: Session = Depends(get_db)):
    from ..pricing_engine import governance
    try:
        result = governance.rollback_champion(db, request.line)
        return {"status": "success", "rolled_back": result["rolled_back"], "champion": result.get("champion")}
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=400, detail=str(e))