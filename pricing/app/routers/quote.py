import asyncio
from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session
from ..database import get_db, Quote
from ..schemas import Product, Profile
from pydantic import BaseModel
from typing import Optional

router = APIRouter(prefix="/pricing", tags=["pricing"])
quote_semaphore = asyncio.Semaphore(100)

class QuoteRequest(BaseModel):
    product_id: str
    profile: dict
    model: Optional[str] = None

@router.post("/quote")
async def create_quote(request: QuoteRequest, db: Session = Depends(get_db)):
    if quote_semaphore.locked():
        raise HTTPException(status_code=503, detail="SERVICE_OVERLOADED")
        
    async with quote_semaphore:
        try:
            from ..pricing_engine.engine import quote
            result = quote(db, request.product_id, request.profile, request.model)
            
            # Persist quote
            db_quote = Quote(
                quote_id=result["quote_id"],
                customer_id="dummy_customer", # Should be from auth token
                product_id=request.product_id,
                line="dummy_line",
                pure_premium_vnd=result["pure_premium_vnd"],
                final_premium_vnd=result["final_premium_vnd"],
                expires_at=result["expires_at"],
                created_at=result["expires_at"] # Use created_at
            )
            # Add dummy values to db_quote for persistence...
            
            db.commit()
            return result
        except ValueError as e:
            db.rollback()
            if str(e) == "UNSUPPORTED_LINE":
                raise HTTPException(status_code=400, detail="UNSUPPORTED_LINE")
            raise HTTPException(status_code=400, detail=str(e))
        except Exception as e:
            db.rollback()
            raise HTTPException(status_code=500, detail=str(e))
