from .database import get_db, Quote, AuditTrail, ModelVersion, ChampionAssignment
from .schemas import Product, Profile
from fastapi import APIRouter
from . import segment, quote, compare

router = APIRouter()
router.include_router(segment.router)
router.include_router(quote.router)
router.include_router(compare.router)
