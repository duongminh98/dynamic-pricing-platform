"""RabbitMQ consumer for ClaimSettled events.

Pricing-service was previously publish-only (outbox). This module adds
a consumer that listens on `claim.settled.queue` and upserts the
`claim_outcome` read-model table. Idempotent by `claim_id` PK Ã¢â‚¬â€
re-emitted events (e.g. from misrepresentation adjustment) update the
existing row.
"""
from __future__ import annotations

import json
import logging
import os
import datetime
import threading

from ..database import SessionLocal, ClaimOutcome, Quote
from ..services.quote_ready_profile import rebuild_quote_ready_profile
from ..services.quote_ready_profile import rebuild_quote_ready_profile

logger = logging.getLogger(__name__)

_consumer_thread: threading.Thread | None = None
_stop_event: threading.Event | None = None

EVENTS_EXCHANGE = os.environ.get("RABBITMQ_EVENTS_EXCHANGE", "platform.events")
CLAIM_SETTLED_QUEUE = os.environ.get("PRICING_CLAIM_SETTLED_QUEUE", "claim.settled.queue")

def _parse_dt(value):
    if not value:
        return None
    try:
        return datetime.datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except (ValueError, TypeError):
        return None

def _int_or_none(value):
    if value in (None, ""):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def upsert_claim_outcome(payload: dict) -> None:
    """Upsert a ClaimSettled payload into claim_outcome.

    Idempotent by claim_id: if the row exists, update actual_loss_vnd
    and settled_at (supports re-emit from misrepresentation adjustments).
    """
    claim_id = payload.get("claim_id")
    if not claim_id:
        logger.warning("ClaimSettled event missing claim_id, skipping")
        return

    db = SessionLocal()
    try:
        existing = db.query(ClaimOutcome).filter(
            ClaimOutcome.claim_id == str(claim_id)
        ).first()

        now = datetime.datetime.now(datetime.timezone.utc)
        quote_id = str(payload.get("quote_id")) if payload.get("quote_id") else None
        customer_id = str(payload.get("customer_id")) if payload.get("customer_id") else None
        line = payload.get("line")

        if quote_id and (not customer_id or not line):
            quote = db.query(Quote).filter(Quote.quote_id == quote_id).first()
            if quote:
                customer_id = customer_id or quote.customer_id
                line = line or quote.line

        settled_at = _parse_dt(payload.get("settled_at")) or now
        occurrence_date = _parse_dt(payload.get("occurrence_date"))
        reported_at = _parse_dt(payload.get("reported_at") or payload.get("report_date"))

        incurred_amount = _int_or_none(payload.get("incurred_amount_vnd"))
        paid_amount = _int_or_none(payload.get("paid_amount_vnd"))
        actual_loss = paid_amount

        if existing:
            existing.customer_id = customer_id or existing.customer_id
            existing.quote_id = quote_id or existing.quote_id
            existing.policy_id = str(payload.get("policy_id")) if payload.get("policy_id") else existing.policy_id
            existing.exposure_segment_seq = _int_or_none(payload.get("exposure_segment_seq")) if payload.get("exposure_segment_seq") is not None else existing.exposure_segment_seq
            existing.line = line or existing.line
            existing.loss_type = payload.get("loss_type") or existing.loss_type
            existing.incurred_amount_vnd = incurred_amount if incurred_amount is not None else existing.incurred_amount_vnd
            existing.paid_amount_vnd = paid_amount if paid_amount is not None else existing.paid_amount_vnd
            existing.actual_loss_vnd = actual_loss if actual_loss is not None else existing.actual_loss_vnd
            existing.claim_status = payload.get("claim_status") or payload.get("status") or existing.claim_status
            existing.occurrence_date = occurrence_date or existing.occurrence_date
            existing.reported_at = reported_at or existing.reported_at
            existing.settled_at = settled_at or existing.settled_at
            existing.recorded_at = now
        else:
            db.add(ClaimOutcome(
                claim_id=str(claim_id),
                customer_id=customer_id,
                quote_id=quote_id,
                policy_id=str(payload.get("policy_id")) if payload.get("policy_id") else None,
                exposure_segment_seq=_int_or_none(payload.get("exposure_segment_seq")),
                line=line,
                loss_type=payload.get("loss_type"),
                incurred_amount_vnd=incurred_amount,
                paid_amount_vnd=paid_amount,
                actual_loss_vnd=actual_loss if actual_loss is not None else None,
                claim_status=payload.get("claim_status") or payload.get("status"),
                occurrence_date=occurrence_date,
                reported_at=reported_at,
                settled_at=settled_at,
                recorded_at=now,
            ))
        if customer_id and line:
            rebuild_quote_ready_profile(db, customer_id, line, last_claim_event_id=str(claim_id))
        db.commit()
        logger.info(f"Upserted claim_outcome for claim_id={claim_id}")
    except Exception:
        db.rollback()
        logger.exception(f"Failed to upsert claim_outcome for claim_id={claim_id}")
        raise
    finally:
        db.close()


def _consume_loop(stop_event: threading.Event) -> None:
    """Background loop that consumes from claim.settled.queue."""
    import pika

    host = os.environ.get("RABBITMQ_HOST", "localhost")
    port = int(os.environ.get("RABBITMQ_PORT", "5672"))
    user = os.environ.get("RABBITMQ_USER", "platform_user")
    password = os.environ.get("RABBITMQ_PASSWORD", "platform_password_dev_only")

    while not stop_event.is_set():
        try:
            credentials = pika.PlainCredentials(user, password)
            params = pika.ConnectionParameters(
                host=host, port=port, credentials=credentials,
                heartbeat=30, blocked_connection_timeout=300,
            )
            connection = pika.BlockingConnection(params)
            channel = connection.channel()
            channel.basic_qos(prefetch_count=10)

            def on_message(ch, method, properties, body):
                try:
                    payload = json.loads(body)
                    upsert_claim_outcome(payload)
                    ch.basic_ack(delivery_tag=method.delivery_tag)
                except Exception:
                    logger.exception("Error processing ClaimSettled message")
                    ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)

            channel.basic_consume(queue=CLAIM_SETTLED_QUEUE, on_message_callback=on_message)
            logger.info("ClaimSettled consumer started on %s", CLAIM_SETTLED_QUEUE)

            while not stop_event.is_set():
                connection.process_data_events(time_limit=1)

            channel.stop_consuming()
            connection.close()
        except Exception:
            logger.exception("ClaimSettled consumer connection failed, retrying in 5s")
            stop_event.wait(5)


def start_consumer() -> None:
    """Start the ClaimSettled consumer in a background thread."""
    global _consumer_thread, _stop_event
    if _consumer_thread is not None and _consumer_thread.is_alive():
        return
    _stop_event = threading.Event()
    _consumer_thread = threading.Thread(
        target=_consume_loop, args=(_stop_event,), daemon=True
    )
    _consumer_thread.start()
    logger.info("ClaimSettled consumer thread started")


def stop_consumer() -> None:
    """Stop the ClaimSettled consumer thread."""
    global _stop_event, _consumer_thread
    if _stop_event is not None:
        _stop_event.set()
    if _consumer_thread is not None:
        _consumer_thread.join(timeout=10)
        _consumer_thread = None
    _stop_event = None


