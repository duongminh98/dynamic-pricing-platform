import asyncio
import hashlib
import uuid
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from sqlalchemy.orm import Session
from ..database import get_db, Quote, QuoteFeatureSnapshot, EventOutbox
from ..config import QUOTE_MAX_CONCURRENCY
from pydantic import BaseModel
from common.errors import ErrorCode, ServiceException
from common.auth import optional_subject
import datetime
from ..pricing_engine.loader import get_line_for_product
from ..services.claim_history import aggregate_claim_history, enrich_profile_with_claim_history
from ..services.customer_profile import merge_customer_risk_profile
from ..services.quote_ready_profile import get_quote_ready_profile, rebuild_quote_ready_profile

router = APIRouter(prefix="/pricing", tags=["pricing"])
quote_semaphore = asyncio.Semaphore(QUOTE_MAX_CONCURRENCY)


def customer_id_from_subject(subject: str) -> str:
    digest = bytearray(hashlib.md5(subject.encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


class QuoteRequest(BaseModel):
    product_id: str
    profile: dict | None = None


@router.post("/quote")
async def create_quote(request: QuoteRequest, http_request: Request, db: Session = Depends(get_db)):
    if quote_semaphore.locked():
        raise HTTPException(status_code=503, detail={"error_code": "SERVICE_OVERLOADED", "message": "Service overloaded"})

    # Customer calls via Kong with JWT Ã¢â€ â€™ sub identifies the owner.
    # Internal calls (order-service re-rate) have no JWT Ã¢â€ â€™ "internal".
    subject = optional_subject(http_request)
    customer_id = customer_id_from_subject(subject) if subject else "internal"

    def _do_quote():
        try:
            from ..pricing_engine.engine import quote
            line = get_line_for_product(request.product_id)
            if customer_id != "internal" and not request.profile:
                enriched_profile = get_quote_ready_profile(db, customer_id, line)
                if enriched_profile is None:
                    rebuilt = rebuild_quote_ready_profile(db, customer_id, line)
                    enriched_profile = dict(rebuilt.enriched_profile or {}) if rebuilt else None
                if enriched_profile is None:
                    raise ServiceException(
                        ErrorCode.MISSING_FEATURES,
                        details={"reason": "quote-ready profile is unavailable; update customer profile first"},
                    )
            else:
                profile_with_cached_customer = merge_customer_risk_profile(db, customer_id, line, request.profile or {})
                claim_features = aggregate_claim_history(db, customer_id, line)
                enriched_profile = enrich_profile_with_claim_history(profile_with_cached_customer, claim_features)
            result = quote(db, request.product_id, enriched_profile)

            db_quote = Quote(
                quote_id=result["quote_id"],
                customer_id=customer_id,
                product_id=request.product_id,
                line=result["line"],
                trip_duration_days=result.get("trip_duration_days"),
                coverage_amount_vnd=result.get("coverage_amount_vnd", 0),
                deductible_vnd=result.get("deductible_vnd", 0),
                geo_risk_version_id=result.get("geo_risk_version_id"),
                cost_index_version_id=result.get("cost_index_version_id"),
                profile=enriched_profile,
                pure_premium_vnd=result["pure_premium_vnd"],
                final_premium_vnd=result["final_premium_vnd"],
                explanation=result.get("explanation"),
                expires_at=datetime.datetime.fromisoformat(result["expires_at"]),
                created_at=datetime.datetime.fromisoformat(result["created_at"]),
            )
            db.add(db_quote)
            from ..pricing_engine.features import feature_set_for_audit
            from ..pricing_engine.loader import required_columns
            db.add(QuoteFeatureSnapshot(
                quote_id=result["quote_id"],
                customer_id=customer_id,
                product_id=request.product_id,
                line=result["line"],
                input_profile=request.profile or {},
                enriched_profile=enriched_profile,
                feature_set=feature_set_for_audit(result["line"], request.product_id, enriched_profile, required_columns(result["line"])),
                model_version_id=result.get("model_version"),
                rate_version_id=result.get("rate_version"),
                created_at=datetime.datetime.fromisoformat(result["created_at"]),
            ))
            db.add(EventOutbox(
                event_id=str(uuid.uuid4()),
                event_type="QuoteCreated",
                routing_key="QuoteCreated",
                payload={
                    "quote_id": result["quote_id"],
                    "customer_id": customer_id,
                    "product_id": request.product_id,
                    "line": result["line"],
                    "trip_duration_days": result.get("trip_duration_days"),
                    "coverage_amount_vnd": result.get("coverage_amount_vnd", 0),
                    "deductible_vnd": result.get("deductible_vnd", 0),
                    "profile": enriched_profile,
                    "final_premium_vnd": result["final_premium_vnd"],
                    "expires_at": result["expires_at"],
                    "created_at": result["created_at"],
                },
                status="NEW",
                created_at=datetime.datetime.now(datetime.timezone.utc),
            ))
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

    async with quote_semaphore:
        return await run_in_threadpool(_do_quote)


@router.get('/quote/{quote_id}')
async def get_quote(quote_id: str, http_request: Request, db: Session = Depends(get_db)):
    db_quote = db.query(Quote).filter(Quote.quote_id == quote_id).first()
    if db_quote is None:
        raise HTTPException(status_code=404, detail={'error_code': 'QUOTE_NOT_FOUND', 'message': 'Quote not found'})

    # Ownership: if caller has JWT (customer), only allow reading own quotes.
    # Internal callers (no JWT, e.g. order-service re-rate) can read any quote.
    subject = optional_subject(http_request)
    if subject is not None and db_quote.customer_id != customer_id_from_subject(subject):
        raise HTTPException(status_code=404, detail={'error_code': 'QUOTE_NOT_FOUND', 'message': 'Quote not found'})

    return {
        'quote_id': db_quote.quote_id,
        'product_id': db_quote.product_id,
        'line': db_quote.line,
        'trip_duration_days': db_quote.trip_duration_days,
        'coverage_amount_vnd': db_quote.coverage_amount_vnd or 0,
        'deductible_vnd': db_quote.deductible_vnd or 0,
        'geo_risk_version_id': db_quote.geo_risk_version_id,
        'cost_index_version_id': db_quote.cost_index_version_id,
        'profile': db_quote.profile or {},
        'pure_premium_vnd': db_quote.pure_premium_vnd,
        'final_premium_vnd': db_quote.final_premium_vnd,
        'explanation': db_quote.explanation or {"available": False, "items": []},
        'expires_at': db_quote.expires_at.isoformat(),
        'created_at': db_quote.created_at.isoformat(),
    }


