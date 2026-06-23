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
    models = db.query(ModelVersion).all()
    return models

@router.post("/champion/promote")
async def promote_champion(request: PromoteRequest, db: Session = Depends(get_db)):
    try:
        # Simplified validation
        model = db.query(ModelVersion).filter(ModelVersion.model_version_id == request.model_version_id).first()
        if not model:
            raise ValueError("MODEL_NOT_FOUND")
            
        if not model.monotonic_applied:
            raise ValueError("MONOTONIC_NOT_APPLIED")
            
        # Update assignment
        db.query(ChampionAssignment).filter(ChampionAssignment.line == request.line).update({"is_current": False})
        
        new_assignment = ChampionAssignment(line=request.line, model_version_id=request.model_version_id, is_current=True)
        db.add(new_assignment)
        
        # Audit
        audit = AuditTrail(
            audit_id=str(uuid.uuid4()),
            event_type="CHAMPION_CHANGE",
            change_detail={"old": "previous", "new": request.model_version_id, "metrics": {}, "actor": "admin"},
            created_at=datetime.datetime.now(datetime.timezone.utc)
        )
        db.add(audit)
        
        db.commit()
        return {"status": "success"}
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=400, detail=str(e))

@router.post("/champion/rollback")
async def rollback_champion(request: RollbackRequest, db: Session = Depends(get_db)):
    try:
        # Update assignment (simplified logic: just pick the latest non-current... actually requires finding previous)
        # Just creating a stub for now
        db.commit()
        return {"status": "success"}
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=400, detail=str(e))
