"""
Health endpoint for FastAPI.

Returns {"status": "healthy"} with 200 status.
Must respond within 5 seconds (R19.1, R19.2).

Requirements: R19.1, R19.2
"""

from fastapi import APIRouter

health_router = APIRouter(tags=["health"])


@health_router.get("/health", summary="Service health check")
async def health_check() -> dict:
    """
    Health check endpoint.
    Returns 200 if the service is running and can respond.
    Kong uses this for active health checks (R17.4).
    """
    return {"status": "healthy"}
