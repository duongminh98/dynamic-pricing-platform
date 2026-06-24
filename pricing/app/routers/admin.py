from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from ..database import get_db, ModelVersion, ChampionAssignment, AuditTrail
from common.auth import require_administrator
from pydantic import BaseModel
import datetime
import uuid

router = APIRouter(prefix="/admin", tags=["admin"])
models_router = APIRouter(prefix="/pricing", tags=["pricing"])


class PromoteRequest(BaseModel):
    line: str
    model_version_id: str


class RollbackRequest(BaseModel):
    line: str


@models_router.get("/models")
async def list_models(db: Session = Depends(get_db), _claims=Depends(require_administrator)):
    return db.query(ModelVersion).all()


@router.post("/champion/promote")
async def promote_champion(request: PromoteRequest, db: Session = Depends(get_db), _claims=Depends(require_administrator)):
    from ..pricing_engine import governance
    from common.errors import ServiceException
    try:
        result = governance.promote_champion(db, request.line, request.model_version_id)
        return {"status": "success", "promoted": result["promoted"], "champion": result.get("champion")}
    except ServiceException:
        db.rollback()
        raise
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=500, detail={"error_code": "INTERNAL_ERROR", "message": str(e)})


@router.post("/champion/rollback")
async def rollback_champion(request: RollbackRequest, db: Session = Depends(get_db), _claims=Depends(require_administrator)):
    from ..pricing_engine import governance
    from common.errors import ServiceException
    try:
        result = governance.rollback_champion(db, request.line)
        return {"status": "success", "rolled_back": result["rolled_back"], "champion": result.get("champion")}
    except ServiceException:
        db.rollback()
        raise
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=500, detail={"error_code": "INTERNAL_ERROR", "message": str(e)})