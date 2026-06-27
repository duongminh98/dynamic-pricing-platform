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

def _model_to_dict(mv: ModelVersion, is_champion: bool) -> dict:
    return {
        "model_version_id": mv.model_version_id,
        "line": mv.line,
        "algorithm": mv.algorithm,
        "gini": mv.gini,
        "rmse": mv.rmse,
        "mae": mv.mae,
        "deviance": mv.deviance,
        "trained_at": mv.trained_at.isoformat() if mv.trained_at else None,
        "dataset_desc": mv.dataset_desc,
        "monotonic_applied": mv.monotonic_applied,
        "is_champion": is_champion,
    }

@models_router.get("/models")
async def list_models(line: str | None = None, db: Session = Depends(get_db), _claims=Depends(require_administrator)):
    query = db.query(ModelVersion)
    if line:
        query = query.filter(ModelVersion.line == line)
    models = query.all()
    champion_ids = {
        a.model_version_id for a in db.query(ChampionAssignment).filter(
            ChampionAssignment.is_current.is_(True)
        ).all()
    }
    return [_model_to_dict(mv, mv.model_version_id in champion_ids) for mv in models]

@router.post("/champion/promote")
async def promote_champion(request: PromoteRequest, db: Session = Depends(get_db), claims=Depends(require_administrator)):
    from ..pricing_engine import governance
    from common.errors import ServiceException
    actor = claims.get("sub", "admin")
    try:
        result = governance.promote_champion(db, request.line, request.model_version_id, actor=actor)
        resp = {"status": "success", "promoted": result["promoted"], "champion": result.get("champion")}
        if not result["promoted"]:
            resp["reason"] = result.get("reason")
        return resp
    except ServiceException:
        db.rollback()
        raise
    except Exception as e:
        db.rollback()
        raise HTTPException(status_code=500, detail={"error_code": "INTERNAL_ERROR", "message": str(e)})

@router.post("/champion/rollback")
async def rollback_champion(request: RollbackRequest, db: Session = Depends(get_db), claims=Depends(require_administrator)):
    from ..pricing_engine import governance
    from common.errors import ServiceException
    actor = claims.get("sub", "admin")
    try:
        result = governance.rollback_champion(db, request.line, actor=actor)
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
