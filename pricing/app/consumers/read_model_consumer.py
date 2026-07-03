"""Pricing read-model consumers for customer profile and policy lifecycle events."""
from __future__ import annotations

import datetime
import json
import logging
import os
import threading
from typing import Any

from sqlalchemy.orm import Session

from ..database import SessionLocal, CustomerRiskProfile, PolicyExposure, Quote, QuoteFeatureSnapshot, EventOutbox
from ..services.product_read_model import (
    upsert_cost_index_version_activated,
    upsert_geo_risk_version_activated,
    upsert_product_catalog_item,
    upsert_rate_version_activated,
)
from ..services.quote_ready_profile import rebuild_quote_ready_profiles
from ..services.quote_ready_profile import rebuild_quote_ready_profiles

logger = logging.getLogger(__name__)

EVENTS_EXCHANGE = os.environ.get("RABBITMQ_EVENTS_EXCHANGE", "platform.events")
PROFILE_QUEUE = os.environ.get("PRICING_PROFILE_QUEUE", "pricing.customer.profile.queue")
POLICY_QUEUE = os.environ.get("PRICING_POLICY_QUEUE", "pricing.policy.events.queue")
PRODUCT_QUEUE = os.environ.get("PRICING_PRODUCT_QUEUE", "pricing.product.events.queue")
REPRICE_QUEUE = os.environ.get("PRICING_REPRICE_QUEUE", "pricing.reprice.requested.queue")
POLICY_EVENT_TYPES = ("PolicyIssued", "PolicyRenewed", "EndorsementApplied", "PolicyCancelled")
PROFILE_EVENT_TYPES = ("CustomerProfileUpdated",)
PRODUCT_EVENT_TYPES = ("ProductUpdated", "RateVersionActivated", "GeoRiskVersionActivated", "CostIndexVersionActivated")
REPRICE_EVENT_TYPES = ("RepriceRequested",)

_consumer_thread: threading.Thread | None = None
_stop_event: threading.Event | None = None


def _now() -> datetime.datetime:
    return datetime.datetime.now(datetime.timezone.utc)


def _parse_dt(value: Any, default: datetime.datetime | None = None) -> datetime.datetime | None:
    if value in (None, ""):
        return default
    if isinstance(value, datetime.datetime):
        return value if value.tzinfo else value.replace(tzinfo=datetime.timezone.utc)
    try:
        return datetime.datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return default


def _json_object(value: Any) -> dict:
    if value is None:
        return {}
    if isinstance(value, dict):
        return value
    if isinstance(value, str) and value.strip():
        try:
            parsed = json.loads(value)
            return parsed if isinstance(parsed, dict) else {}
        except json.JSONDecodeError:
            return {}
    return {}


def _int_or_none(value: Any) -> int | None:
    if value in (None, ""):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _float_or_default(value: Any, default: float) -> float:
    if value in (None, ""):
        return default
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def _str_or_none(value: Any) -> str | None:
    return str(value) if value not in (None, "") else None


def _event_id(payload: dict) -> str | None:
    return _str_or_none(payload.get("event_id") or payload.get("id"))


def upsert_customer_risk_profile(payload: dict) -> None:
    customer_id = _str_or_none(payload.get("customer_id"))
    if not customer_id:
        logger.warning("CustomerProfileUpdated event missing customer_id, skipping")
        return

    profile_version = _int_or_none(payload.get("profile_version")) or 0
    effective_at = _parse_dt(payload.get("effective_at") or payload.get("occurred_at"), _now())
    common_attrs = _json_object(payload.get("common_risk_attributes") or payload.get("risk_attributes") or payload.get("profile"))
    line_attrs = _json_object(payload.get("line_risk_attributes"))
    event_id = _event_id(payload)
    now = _now()

    db = SessionLocal()
    try:
        existing = db.query(CustomerRiskProfile).filter(CustomerRiskProfile.customer_id == customer_id).first()
        if existing and profile_version < existing.profile_version:
            logger.info("Skipping stale CustomerProfileUpdated customer_id=%s version=%s current=%s",
                        customer_id, profile_version, existing.profile_version)
            db.commit()
            return
        if existing:
            existing.profile_version = profile_version
            existing.effective_at = effective_at
            existing.common_risk_attributes = common_attrs
            existing.line_risk_attributes = line_attrs
            existing.last_event_id = event_id or existing.last_event_id
            existing.updated_at = now
        else:
            db.add(CustomerRiskProfile(
                customer_id=customer_id,
                profile_version=profile_version,
                effective_at=effective_at,
                common_risk_attributes=common_attrs,
                line_risk_attributes=line_attrs,
                last_event_id=event_id,
                updated_at=now,
            ))
        rebuild_quote_ready_profiles(db, customer_id)
        db.commit()
    except Exception:
        db.rollback()
        logger.exception("Failed to upsert customer_risk_profile customer_id=%s", customer_id)
        raise
    finally:
        db.close()


def _exposure_id(policy_id: str, seq: int) -> str:
    return f"{policy_id}:{seq}"


def _upsert_policy_exposure_row(db: Session, payload: dict, event_type: str, status: str | None = None) -> None:
    policy_id = _str_or_none(payload.get("policy_id"))
    customer_id = _str_or_none(payload.get("customer_id"))
    line = _str_or_none(payload.get("line"))
    seq = _int_or_none(payload.get("exposure_segment_seq"))
    if not policy_id or not customer_id or not line or seq is None:
        logger.warning("%s event missing policy exposure keys, skipping", event_type)
        return

    segment_start = _parse_dt(payload.get("segment_start") or payload.get("new_effective_date") or payload.get("policy_effective_date"), _now())
    segment_end = _parse_dt(payload.get("segment_end") or payload.get("new_expiration_date") or payload.get("policy_expiration_date"), segment_start)
    earned = _float_or_default(payload.get("earned_exposure_years"), 0.0)
    if earned <= 0 and segment_start and segment_end:
        earned = max(0.0, (segment_end - segment_start).days / 365.25)

    exposure_id = _str_or_none(payload.get("exposure_id")) or _exposure_id(policy_id, seq)
    existing = db.query(PolicyExposure).filter(PolicyExposure.exposure_id == exposure_id).first()
    row_values = {
        "policy_id": policy_id,
        "quote_id": _str_or_none(payload.get("quote_id")),
        "customer_id": customer_id,
        "product_id": _str_or_none(payload.get("product_id")),
        "line": line,
        "exposure_segment_seq": seq,
        "segment_start": segment_start,
        "segment_end": segment_end,
        "earned_exposure_years": earned,
        "coverage_amount_vnd": _int_or_none(payload.get("coverage_amount_vnd")),
        "deductible_vnd": _int_or_none(payload.get("deductible_vnd")),
        "final_premium_vnd": _int_or_none(payload.get("final_premium_vnd") or payload.get("renewed_premium_vnd") or payload.get("premium_new_vnd")),
        "status": status or _str_or_none(payload.get("status")) or "active",
        "risk_snapshot": _json_object(payload.get("risk_snapshot") or payload.get("profile") or payload.get("risk_profile")),
        "source_event_type": event_type,
        "recorded_at": _now(),
    }
    if existing:
        for key, value in row_values.items():
            if value is not None:
                setattr(existing, key, value)
    else:
        db.add(PolicyExposure(exposure_id=exposure_id, **row_values))


def _close_policy_exposures(db: Session, payload: dict) -> None:
    policy_id = _str_or_none(payload.get("policy_id"))
    if not policy_id:
        logger.warning("PolicyCancelled event missing policy_id, skipping")
        return
    cancel_date = _parse_dt(payload.get("cancel_date"), _now())
    rows = db.query(PolicyExposure).filter(PolicyExposure.policy_id == policy_id).all()
    for row in rows:
        row.status = "cancelled"
        if cancel_date and row.segment_start and row.segment_end:
            if row.segment_start <= cancel_date < row.segment_end:
                row.segment_end = cancel_date
                row.earned_exposure_years = max(0.0, (cancel_date - row.segment_start).days / 365.25)
            elif row.segment_start > cancel_date:
                row.segment_end = row.segment_start
                row.earned_exposure_years = 0.0
        row.source_event_type = "PolicyCancelled"
        row.recorded_at = _now()


def upsert_policy_event(payload: dict, event_type: str | None = None) -> None:
    resolved_type = event_type or _str_or_none(payload.get("event_type")) or _str_or_none(payload.get("type"))
    if resolved_type not in POLICY_EVENT_TYPES:
        logger.warning("Unsupported policy event type=%s", resolved_type)
        return
    db = SessionLocal()
    try:
        if resolved_type == "PolicyCancelled":
            _close_policy_exposures(db, payload)
        else:
            _upsert_policy_exposure_row(db, payload, resolved_type)
        db.commit()
    except Exception:
        db.rollback()
        logger.exception("Failed to process policy event type=%s", resolved_type)
        raise
    finally:
        db.close()

def upsert_product_event(payload: dict, event_type: str | None = None) -> None:
    resolved_type = event_type or _str_or_none(payload.get("event_type")) or _str_or_none(payload.get("type"))
    if resolved_type not in PRODUCT_EVENT_TYPES:
        logger.warning("Unsupported product event type=%s", resolved_type)
        return
    db = SessionLocal()
    try:
        if resolved_type == "ProductUpdated":
            upsert_product_catalog_item(db, payload)
        elif resolved_type == "RateVersionActivated":
            upsert_rate_version_activated(db, payload)
        elif resolved_type == "GeoRiskVersionActivated":
            upsert_geo_risk_version_activated(db, payload)
        elif resolved_type == "CostIndexVersionActivated":
            upsert_cost_index_version_activated(db, payload)
        db.commit()
    except Exception:
        db.rollback()
        logger.exception("Failed to process product event type=%s", resolved_type)
        raise
    finally:
        db.close()


def process_reprice_requested(payload: dict) -> None:
    pricing_request_id = _str_or_none(payload.get("pricing_request_id"))
    product_id = _str_or_none(payload.get("product_id"))
    workflow = _str_or_none(payload.get("workflow")) or "UNKNOWN"
    customer_id = _str_or_none(payload.get("customer_id")) or "internal"
    profile = _json_object(payload.get("profile"))
    if not pricing_request_id or not product_id:
        logger.warning("RepriceRequested event missing required fields, skipping")
        return

    db = SessionLocal()
    now = _now()
    try:
        from ..pricing_engine.engine import quote
        from ..pricing_engine.features import feature_set_for_audit
        from ..pricing_engine.loader import required_columns

        result = quote(db, product_id, profile)
        db.add(Quote(
            quote_id=result["quote_id"],
            customer_id=customer_id,
            product_id=product_id,
            line=result["line"],
            trip_duration_days=result.get("trip_duration_days"),
            coverage_amount_vnd=result.get("coverage_amount_vnd", 0),
            deductible_vnd=result.get("deductible_vnd", 0),
            geo_risk_version_id=result.get("geo_risk_version_id"),
            cost_index_version_id=result.get("cost_index_version_id"),
            profile=profile,
            pure_premium_vnd=result["pure_premium_vnd"],
            final_premium_vnd=result["final_premium_vnd"],
            explanation=result.get("explanation"),
            expires_at=datetime.datetime.fromisoformat(result["expires_at"]),
            created_at=datetime.datetime.fromisoformat(result["created_at"]),
        ))
        db.add(QuoteFeatureSnapshot(
            quote_id=result["quote_id"],
            customer_id=customer_id,
            product_id=product_id,
            line=result["line"],
            input_profile=profile,
            enriched_profile=profile,
            feature_set=feature_set_for_audit(result["line"], product_id, profile, required_columns(result["line"])),
            model_version_id=result.get("model_version"),
            rate_version_id=result.get("rate_version"),
            created_at=datetime.datetime.fromisoformat(result["created_at"]),
        ))
        completed_payload = {
            "pricing_request_id": pricing_request_id,
            "workflow": workflow,
            "customer_id": customer_id,
            "policy_id": _str_or_none(payload.get("policy_id")),
            "aggregate_id": _str_or_none(payload.get("aggregate_id")),
            "quote_id": result["quote_id"],
            "product_id": product_id,
            "line": result["line"],
            "final_premium_vnd": result["final_premium_vnd"],
            "created_at": result["created_at"],
        }
        db.add(EventOutbox(
            event_id=str(__import__("uuid").uuid4()),
            event_type="RepriceCompleted",
            routing_key="RepriceCompleted",
            payload=completed_payload,
            status="NEW",
            created_at=now,
        ))
        db.commit()
    except Exception as exc:
        db.rollback()
        try:
            db.add(EventOutbox(
                event_id=str(__import__("uuid").uuid4()),
                event_type="RepriceCompleted",
                routing_key="RepriceCompleted",
                payload={
                    "pricing_request_id": pricing_request_id,
                    "workflow": workflow,
                    "customer_id": customer_id,
                    "policy_id": _str_or_none(payload.get("policy_id")),
                    "aggregate_id": _str_or_none(payload.get("aggregate_id")),
                    "failure_reason": str(exc)[:500],
                    "created_at": now.isoformat(),
                },
                status="NEW",
                created_at=now,
            ))
            db.commit()
        except Exception:
            db.rollback()
            logger.exception("Failed to publish RepriceCompleted failure")
            raise
    finally:
        db.close()

def _consume_loop(stop_event: threading.Event) -> None:
    import pika

    host = os.environ.get("RABBITMQ_HOST", "localhost")
    port = int(os.environ.get("RABBITMQ_PORT", "5672"))
    user = os.environ.get("RABBITMQ_USER", "platform_user")
    password = os.environ.get("RABBITMQ_PASSWORD", "platform_password_dev_only")

    while not stop_event.is_set():
        try:
            credentials = pika.PlainCredentials(user, password)
            params = pika.ConnectionParameters(host=host, port=port, credentials=credentials,
                                               heartbeat=30, blocked_connection_timeout=300)
            connection = pika.BlockingConnection(params)
            channel = connection.channel()
            channel.exchange_declare(exchange=EVENTS_EXCHANGE, exchange_type="topic", durable=True)
            channel.queue_declare(
                queue=REPRICE_QUEUE, durable=True,
                arguments={
                    "x-queue-type": "quorum",
                    "x-dead-letter-exchange": "platform.events.dlx",
                    "x-dead-letter-routing-key": "RepriceRequested",
                    "x-delivery-limit": 3,
                },
            )
            channel.queue_bind(exchange=EVENTS_EXCHANGE, queue=REPRICE_QUEUE, routing_key="RepriceRequested")
            channel.basic_qos(prefetch_count=10)

            def on_profile(ch, method, properties, body):
                try:
                    upsert_customer_risk_profile(json.loads(body))
                    ch.basic_ack(delivery_tag=method.delivery_tag)
                except Exception:
                    logger.exception("Error processing customer profile message")
                    ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)

            def on_policy(ch, method, properties, body):
                try:
                    event_type = getattr(method, "routing_key", None)
                    upsert_policy_event(json.loads(body), event_type)
                    ch.basic_ack(delivery_tag=method.delivery_tag)
                except Exception:
                    logger.exception("Error processing policy lifecycle message")
                    ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)

            def on_product(ch, method, properties, body):
                try:
                    event_type = getattr(method, "routing_key", None)
                    upsert_product_event(json.loads(body), event_type)
                    ch.basic_ack(delivery_tag=method.delivery_tag)
                except Exception:
                    logger.exception("Error processing product catalog/rate message")
                    ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)

            def on_reprice(ch, method, properties, body):
                try:
                    process_reprice_requested(json.loads(body))
                    ch.basic_ack(delivery_tag=method.delivery_tag)
                except Exception:
                    logger.exception("Error processing reprice request message")
                    ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)

            channel.basic_consume(queue=PROFILE_QUEUE, on_message_callback=on_profile)
            channel.basic_consume(queue=POLICY_QUEUE, on_message_callback=on_policy)
            channel.basic_consume(queue=PRODUCT_QUEUE, on_message_callback=on_product)
            channel.basic_consume(queue=REPRICE_QUEUE, on_message_callback=on_reprice)
            logger.info("Pricing read-model consumers started")
            while not stop_event.is_set():
                connection.process_data_events(time_limit=1)
            channel.stop_consuming()
            connection.close()
        except Exception:
            logger.exception("Pricing read-model consumer connection failed, retrying in 5s")
            stop_event.wait(5)


def start_consumer() -> None:
    global _consumer_thread, _stop_event
    if _consumer_thread is not None and _consumer_thread.is_alive():
        return
    _stop_event = threading.Event()
    _consumer_thread = threading.Thread(target=_consume_loop, args=(_stop_event,), daemon=True)
    _consumer_thread.start()
    logger.info("Pricing read-model consumer thread started")


def stop_consumer() -> None:
    global _stop_event, _consumer_thread
    if _stop_event is not None:
        _stop_event.set()
    if _consumer_thread is not None:
        _consumer_thread.join(timeout=10)
        _consumer_thread = None
    _stop_event = None




