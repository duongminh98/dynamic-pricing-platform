import asyncio
from fastapi import APIRouter, Depends, HTTPException, Request
from sqlalchemy.orm import Session
from ..database import get_db, Quote
from pydantic import BaseModel
from common.errors import ServiceException
from common.auth import optional_subject
import datetime

router = APIRouter(prefix="/pricing", tags=["pricing"])
quote_semaphore = asyncio.Semaphore(100)


class QuoteRequest(BaseModel):
    product_id: str
    profile: dict


@router.post("/quote")
async def create_quote(request: QuoteRequest, http_request: Request, db: Session = Depends(get_db)):
    if quote_semaphore.locked():
        raise HTTPException(status_code=503, detail={"error_code": "SERVICE_OVERLOADED", "message": "Service overloaded"})

    # Customer calls via Kong with JWT → sub identifies the owner.
    # Internal calls (order-service re-rate) have no JWT → "internal".
    subject = optional_subject(http_request)
    customer_id = subject if subject else "internal"

    async with quote_semaphore:
        try:
            from ..pricing_engine.engine import quote
            result = quote(db, request.product_id, request.profile)

            db_quote = Quote(
                quote_id=result["quote_id"],
                customer_id=customer_id,
                product_id=request.product_id,
                line=result["line"],
                trip_duration_days=result.get("trip_duration_days"),
                coverage_amount_vnd=result.get("coverage_amount_vnd", 0),
                deductible_vnd=result.get("deductible_vnd", 0),
                profile=request.profile,
                pure_premium_vnd=result["pure_premium_vnd"],
                final_premium_vnd=result["final_premium_vnd"],
                explanation=result.get("explanation"),
                expires_at=datetime.datetime.fromisoformat(result["expires_at"]),
                created_at=datetime.datetime.fromisoformat(result["created_at"]),
            )
            db.add(db_quote)
            db.commit()
            return result
        except HTTPException:
            db.rollback()
            raise
        except ServiceException:
            db.rollback()
            # Let the registered ServiceException handler render the 7.1 body
            # (error_code + message + correlation_id + details) with the right status.
            raise
        except Exception:
            db.rollback()
            raise


@router.get('/quote/{quote_id}')
async def get_quote(quote_id: str, http_request: Request, db: Session = Depends(get_db)):
    db_quote = db.query(Quote).filter(Quote.quote_id == quote_id).first()
    if db_quote is None:
        raise HTTPException(status_code=404, detail={'error_code': 'QUOTE_NOT_FOUND', 'message': 'Quote not found'})

    # Ownership: if caller has JWT (customer), only allow reading own quotes.
    # Internal callers (no JWT, e.g. order-service re-rate) can read any quote.
    subject = optional_subject(http_request)
    if subject is not None and db_quote.customer_id != subject:
        raise HTTPException(status_code=404, detail={'error_code': 'QUOTE_NOT_FOUND', 'message': 'Quote not found'})

    return {
        'quote_id': db_quote.quote_id,
        'product_id': db_quote.product_id,
        'line': db_quote.line,
        'trip_duration_days': db_quote.trip_duration_days,
        'coverage_amount_vnd': db_quote.coverage_amount_vnd or 0,
        'deductible_vnd': db_quote.deductible_vnd or 0,
        'profile': db_quote.profile or {},
        'pure_premium_vnd': db_quote.pure_premium_vnd,
        'final_premium_vnd': db_quote.final_premium_vnd,
        'explanation': db_quote.explanation or {"available": False, "items": []},
        'expires_at': db_quote.expires_at.isoformat(),
        'created_at': db_quote.created_at.isoformat(),
    }

