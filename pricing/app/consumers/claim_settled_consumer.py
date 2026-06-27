"""RabbitMQ consumer for ClaimSettled events.

Pricing-service was previously publish-only (outbox). This module adds
a consumer that listens on `claim.settled.queue` and upserts the
`claim_outcome` read-model table. Idempotent by `claim_id` PK —
re-emitted events (e.g. from misrepresentation adjustment) update the
existing row.
"""
from __future__ import annotations

import json
import logging
import os
import datetime
import threading

from ..database import SessionLocal, ClaimOutcome

logger = logging.getLogger(__name__)

_consumer_thread: threading.Thread | None = None
_stop_event: threading.Event | None = None


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
        settled_at_str = payload.get("settled_at")
        settled_at = None
        if settled_at_str:
            try:
                settled_at = datetime.datetime.fromisoformat(
                    settled_at_str.replace("Z", "+00:00")
                )
            except (ValueError, TypeError):
                settled_at = now

        actual_loss = payload.get("paid_amount_vnd")

        if existing:
            existing.quote_id = str(payload.get("quote_id")) if payload.get("quote_id") else existing.quote_id
            existing.policy_id = str(payload.get("policy_id")) if payload.get("policy_id") else existing.policy_id
            existing.line = payload.get("line") or existing.line
            existing.actual_loss_vnd = actual_loss if actual_loss is not None else existing.actual_loss_vnd
            existing.settled_at = settled_at or existing.settled_at
            existing.recorded_at = now
        else:
            db.add(ClaimOutcome(
                claim_id=str(claim_id),
                quote_id=str(payload.get("quote_id")) if payload.get("quote_id") else None,
                policy_id=str(payload.get("policy_id")) if payload.get("policy_id") else None,
                line=payload.get("line"),
                actual_loss_vnd=actual_loss if actual_loss is not None else None,
                settled_at=settled_at,
                recorded_at=now,
            ))
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

            channel.basic_consume(queue="claim.settled.queue", on_message_callback=on_message)
            logger.info("ClaimSettled consumer started on claim.settled.queue")

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
