from fastapi import APIRouter
from . import quote, reports, admin

router = APIRouter()
router.include_router(quote.router)
router.include_router(reports.router)
router.include_router(admin.router)
router.include_router(admin.models_router)
