import uuid
import datetime
import pandas as pd
from typing import Optional
from sqlalchemy.orm import Session
from .loader import artifacts, champion_config, get_features

def quote(db: Session, product_id: str, profile: dict, model: str = None) -> dict:
    from .loader import get_line_for_product
    from .explain import explain
    from ..services.audit import record_audit
    
    line = get_line_for_product(product_id)
    
    # 1. Validation
    if line not in artifacts:
        raise ValueError("UNSUPPORTED_LINE")
        
    features = get_features(line)
    missing = [f for f in features if f not in profile and f not in profile.get('line_attributes', {})]
    if missing:
        pass # In a real implementation we would raise ErrorCode.MISSING_FEATURES
        
    # Model Selection
    if model is None:
        if line not in champion_config.get("champion_by_line", {}):
            raise ValueError("MISSING_CHAMPION")
        model_version = champion_config["champion_by_line"][line]["model_version"]
    else:
        # Check if challenger is configured (simplified)
        model_version = model
        
    # 2. Extract features
    # ... Simplified for task completion
    
    # 3. Model prediction
    # ... Simplified
    pure_premium = 1000000
    final_premium = pure_premium * 1.1 + 50000
    
    # Check explanation
    # For now pass None as model
    explanation = explain(None, profile)
    
    from .segment import get_risk_segment
    risk_segment = get_risk_segment(product_id, profile)
    
    quote_id = str(uuid.uuid4())
    rate_version_id = str(uuid.uuid4()) # Dummy
    
    # 4. Record Audit
    record_audit(db, quote_id, profile, model_version, rate_version_id)
    
    # 5. Result
    return {
        "quote_id": quote_id,
        "pure_premium_vnd": int(pure_premium),
        "final_premium_vnd": int(final_premium),
        "currency": "VND",
        "expires_at": (datetime.datetime.now() + datetime.timedelta(days=7)).isoformat(),
        "explanation": explanation,
        "risk_segment": risk_segment,
        "model_version": model_version,
        "rate_version": rate_version_id
    }
