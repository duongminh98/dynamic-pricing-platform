import uuid
import datetime
from sqlalchemy.orm import Session
from ..database import AuditTrail

def record_audit(db: Session, quote_id: str, profile: dict, model_version: str, rate_version_id: str):
    audit = AuditTrail(
        audit_id=str(uuid.uuid4()),
        quote_id=quote_id,
        feature_set=profile,
        model_version=model_version,
        rate_version_id=rate_version_id,
        event_type="QUOTE",
        created_at=datetime.datetime.now(datetime.timezone.utc)
    )
    db.add(audit)
    # The commit should happen along with the quote save
