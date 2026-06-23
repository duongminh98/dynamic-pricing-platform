import asyncio
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from ..database import get_db, Quote
from pydantic import BaseModel
import datetime

router = APIRouter(prefix="/pricing", tags=["pricing"])
quote_semaphore = asyncio.Semaphore(100)


class QuoteRequest(BaseModel):
    product_id: str
    profile: dict


@router.post("/quote")
async def create_quote(request: QuoteRequest, db: Session = Depends(get_db)):
    if quote_semaphore.locked():
        raise HTTPException(status_code=503, detail="SERVICE_OVERLOADED")

    async with quote_semaphore:
        try:
            from ..pricing_engine.engine import quote
            result = quote(db, request.product_id, request.profile)

            db_quote = Quote(
                quote_id=result["quote_id"],
                customer_id="anonymous",
                product_id=request.product_id,
                line=result["line"],
                pure_premium_vnd=result["pure_premium_vnd"],
                final_premium_vnd=result["final_premium_vnd"],
                expires_at=datetime.datetime.fromisoformat(result["expires_at"]),
                created_at=datetime.datetime.fromisoformat(result["created_at"]),
            )
            db.add(db_quote)
            db.commit()
            return result
        except HTTPException:
            db.rollback()
            raise
        except Exception as exc:
            db.rollback()
            # Map business exceptions to structured errors via common errors.
            from common.errors import ErrorCode, ServiceException
            if isinstance(exc, ServiceException):
                raise HTTPException(status_code=exc.error_code.http_status,
                                    detail={"error_code": exc.error_code.code,
                                            "message": exc.message,
                                            "details": exc.details})
            raise HTTPException(status_code=400, detail=str(exc))