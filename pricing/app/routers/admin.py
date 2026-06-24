from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from sqlalchemy import func
from ..database import get_db, ModelVersion, ChampionAssignment, AuditTrail, ModelDriftFlag
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

@models_router.get("/drift")
async def get_drift_status(db: Session = Depends(get_db), _claims=Depends(require_administrator)):
    """Return the latest drift flags per line (task 23.2, R37.7).

    Returns a list of per-line drift status with PSI and calibration metrics,
    thresholds, and the needs_recalibration flag. Administrator-only.
    """
    lines = ["health", "motorbike", "car", "home", "accident", "travel"]
    result = []
    for line in lines:
        flags = db.query(ModelDriftFlag).filter(
            ModelDriftFlag.line == line
        ).order_by(ModelDriftFlag.computed_at.desc()).all()
        if not flags:
            result.append({
                "line": line,
                "needs_recalibration": False,
                "metrics": [],
            })
        else:
            metrics = {}
            for f in flags:
                if f.metric not in metrics:
                    metrics[f.metric] = {
                        "value": f.value,
                        "threshold": f.threshold,
                        "needs_recalibration": f.needs_recalibration,
                        "computed_at": f.computed_at.isoformat() if f.computed_at else None,
                    }
            result.append({
                "line": line,
                "needs_recalibration": any(m["needs_recalibration"] for m in metrics.values()),
                "metrics": metrics,
            })
    return result
