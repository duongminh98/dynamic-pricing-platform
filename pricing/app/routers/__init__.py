from .database import get_db, Quote, AuditTrail, ModelVersion, ChampionAssignment
from .schemas import Product, Profile
from fastapi import APIRouter
from . import segment

router = APIRouter()
router.include_router(segment.router)
