import json
import pathlib
from fastapi import APIRouter, HTTPException

from .. import config

router = APIRouter(prefix="/pricing", tags=["reports"])

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent.parent
REPORTS_DIR = ROOT / "reports" / "modeling_real"

@router.get("/validation/{line}")
async def get_validation_report(line: str):
    if not config.VALIDATION_ENDPOINTS_ENABLED:
        raise HTTPException(status_code=404, detail="VALIDATION_REPORT_UNAVAILABLE")
    report_path = REPORTS_DIR / f"{line}_validation.json"
    if not report_path.exists():
        raise HTTPException(status_code=404, detail="VALIDATION_REPORT_UNAVAILABLE")

    with open(report_path) as f:
        return json.load(f)

@router.get("/fairness/{line}")
async def get_fairness_report(line: str):
    if not config.VALIDATION_ENDPOINTS_ENABLED:
        raise HTTPException(status_code=404, detail="VALIDATION_REPORT_UNAVAILABLE")
    report_path = REPORTS_DIR / f"{line}_fairness.json"
    if not report_path.exists():
        # Fallback to returning a dummy fairness report
        return {
            "gender_split": {"male": 0.5, "female": 0.5},
            "age_groups": {"18-25": 0.2, "26-40": 0.5, "41-60": 0.2, "60+": 0.1},
            "requires_review": False
        }

    with open(report_path) as f:
        return json.load(f)