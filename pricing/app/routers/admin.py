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

class RejectCandidateRequest(BaseModel):
    line: str
    model_version_id: str

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
        "family": mv.family,
        "status": mv.status,
        "dataset_version_id": mv.dataset_version_id,
        "artifact_uri": mv.artifact_uri,
        "artifact_checksum": mv.artifact_checksum,
        "feature_schema_hash": mv.feature_schema_hash,
        "comparison_report_uri": mv.comparison_report_uri,
        "validation_report_uri": mv.validation_report_uri,
        "fairness_report_uri": mv.fairness_report_uri,
        "registered_at": mv.registered_at.isoformat() if mv.registered_at else None,
        "registered_by": mv.registered_by,
        "training_code_version": mv.training_code_version,
        "quality_gates": mv.quality_gates,
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

@router.post("/models/reject")
async def reject_candidate(request: RejectCandidateRequest, db: Session = Depends(get_db), claims=Depends(require_administrator)):
    from ..pricing_engine import governance
    from common.errors import ServiceException
    actor = claims.get("sub", "admin")
    try:
        result = governance.reject_candidate(db, request.line, request.model_version_id, actor=actor)
        return {"status": "success", **result}
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
    """Return the latest drift flags per line with 3 metrics.

    Returns a list of per-line drift status with:
    - feature_psi: feature distribution PSI
    - prediction_psi: prediction distribution PSI
    - calibration: calibration drift with status and bins_evaluated
    Each metric has value, threshold, needs_recalibration, computed_at.
    Administrator-only.
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
                "metrics": {
                    "feature_psi": {"value": 0.0, "threshold": 0.2, "needs_recalibration": False},
                    "prediction_psi": {"value": 0.0, "threshold": 0.2, "needs_recalibration": False},
                    "calibration": {"value": 0.0, "threshold": 0.15, "needs_recalibration": False, "status": "no_data", "bins_evaluated": 0},
                },
            })
        else:
            latest_per_metric = {}
            for f in flags:
                if f.metric not in latest_per_metric:
                    entry = {
                        "value": f.value,
                        "threshold": f.threshold,
                        "needs_recalibration": f.needs_recalibration,
                        "computed_at": f.computed_at.isoformat() if f.computed_at else None,
                    }
                    if f.metric == "calibration":
                        entry["status"] = "sufficient_data" if f.value > 0 else "insufficient_data"
                        entry["bins_evaluated"] = 0
                    latest_per_metric[f.metric] = entry

            for required in ("feature_psi", "prediction_psi", "calibration"):
                if required not in latest_per_metric:
                    if required == "calibration":
                        latest_per_metric[required] = {
                            "value": 0.0, "threshold": 0.15, "needs_recalibration": False,
                            "status": "no_data", "bins_evaluated": 0,
                        }
                    else:
                        latest_per_metric[required] = {
                            "value": 0.0, "threshold": 0.2, "needs_recalibration": False,
                        }

            result.append({
                "line": line,
                "needs_recalibration": any(m["needs_recalibration"] for m in latest_per_metric.values()),
                "metrics": latest_per_metric,
            })
    return result
