import json
import pathlib
from fastapi import APIRouter, Depends

from .. import config
from common.auth import require_administrator
from common.errors import ErrorCode, ServiceException

router = APIRouter(prefix="/pricing", tags=["reports"])

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent.parent
REPORTS_DIR = ROOT / "reports" / "modeling_real"

@router.get("/validation/{line}")
async def get_validation_report(line: str, _claims=Depends(require_administrator)):
    if not config.VALIDATION_ENDPOINTS_ENABLED:
        raise ServiceException(ErrorCode.VALIDATION_REPORT_UNAVAILABLE)
    report_path = REPORTS_DIR / f"{line}_validation.json"
    if not report_path.exists():
        raise ServiceException(ErrorCode.VALIDATION_REPORT_UNAVAILABLE)

    with open(report_path) as f:
        return json.load(f)

@router.get("/fairness/{line}")
async def get_fairness_report(line: str, _claims=Depends(require_administrator)):
    if not config.VALIDATION_ENDPOINTS_ENABLED:
        raise ServiceException(ErrorCode.FAIRNESS_REPORT_UNAVAILABLE)
    report_path = REPORTS_DIR / f"{line}_fairness.json"
    if not report_path.exists():
        raise ServiceException(ErrorCode.FAIRNESS_REPORT_UNAVAILABLE)

    with open(report_path) as f:
        return json.load(f)