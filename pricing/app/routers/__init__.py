from fastapi import APIRouter

router = APIRouter()
router.include_router(quote.router)
router.include_router(reports.router)
router.include_router(admin.router)
