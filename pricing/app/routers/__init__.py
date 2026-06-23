from fastapi import APIRouter
from . import segment, quote, compare, reports, admin

router = APIRouter()
router.include_router(segment.router)
router.include_router(quote.router)
router.include_router(compare.router)
router.include_router(reports.router)
router.include_router(admin.router)
