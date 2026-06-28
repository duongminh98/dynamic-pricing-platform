"""RabbitMQ relay for pricing-service transactional outbox events."""
from __future__ import annotations

import json
import logging
import os
import threading
import time
from typing import Any

from sqlalchemy.orm import Session

from .database import EventOutbox, SessionLocal

logger = logging.getLogger(__name__)

EVENTS_EXCHANGE = os.environ.get("RABBITMQ_EVENTS_EXCHANGE", "platform.events")
POLL_INTERVAL_SECONDS = float(os.environ.get("PRICING_OUTBOX_POLL_SECONDS", "2"))
BATCH_SIZE = int(os.environ.get("PRICING_OUTBOX_BATCH_SIZE", "50"))

_relay_thread: threading.Thread | None = None
_stop_event: threading.Event | None = None


def _json_default(value: Any) -> str:
    return str(value)


def _connect():
    import pika

    host = os.environ.get("RABBITMQ_HOST", "localhost")
    port = int(os.environ.get("RABBITMQ_PORT", "5672"))
    user = os.environ.get("RABBITMQ_USER", "platform_user")
    password = os.environ.get("RABBITMQ_PASSWORD", "platform_password_dev_only")

    credentials = pika.PlainCredentials(user, password)
    params = pika.ConnectionParameters(
        host=host,
        port=port,
        credentials=credentials,
        heartbeat=30,
        blocked_connection_timeout=300,
    )
    return pika.BlockingConnection(params)


def _publish_entry(channel, entry: EventOutbox) -> None:
    import pika

    body = json.dumps(entry.payload, default=_json_default).encode("utf-8")
    properties = pika.BasicProperties(
        content_type="application/json",
        content_encoding="utf-8",
        delivery_mode=2,
        message_id=entry.event_id,
        correlation_id=entry.event_id,
        headers={
            "X-Event-Id": entry.event_id,
            "X-Event-Type": entry.event_type,
            "X-Created-At": entry.created_at.isoformat(),
        },
    )
    channel.basic_publish(
        exchange=EVENTS_EXCHANGE,
        routing_key=entry.routing_key,
        body=body,
        properties=properties,
        mandatory=False,
    )


def relay_once(db: Session | None = None, connection=None) -> int:
    """Publish one batch of NEW outbox rows and mark successfully sent rows."""
    owns_db = db is None
    owns_connection = connection is None
    db = db or SessionLocal()
    connection = connection or _connect()
    published = 0
    channel = None
    try:
        channel = connection.channel()
        pending = (
            db.query(EventOutbox)
            .filter(EventOutbox.status == "NEW")
            .order_by(EventOutbox.created_at.asc())
            .limit(BATCH_SIZE)
            .all()
        )
        for entry in pending:
            try:
                _publish_entry(channel, entry)
                entry.status = "SENT"
                db.commit()
                published += 1
                logger.info("Published pricing outbox event %s type=%s", entry.event_id, entry.event_type)
            except Exception:
                db.rollback()
                logger.exception("Failed to publish pricing outbox event %s", entry.event_id)
        return published
    finally:
        if channel is not None and hasattr(channel, "close"):
            try:
                channel.close()
            except Exception:
                logger.debug("Failed to close RabbitMQ channel", exc_info=True)
        if owns_connection:
            try:
                connection.close()
            except Exception:
                logger.debug("Failed to close RabbitMQ connection", exc_info=True)
        if owns_db:
            db.close()


def _relay_loop(stop_event: threading.Event) -> None:
    while not stop_event.is_set():
        try:
            relay_once()
        except Exception:
            logger.exception("Pricing outbox relay failed, retrying")
        stop_event.wait(POLL_INTERVAL_SECONDS)


def start_outbox_relay() -> None:
    """Start the pricing outbox relay in a background thread."""
    global _relay_thread, _stop_event
    if _relay_thread is not None and _relay_thread.is_alive():
        return
    _stop_event = threading.Event()
    _relay_thread = threading.Thread(target=_relay_loop, args=(_stop_event,), daemon=True)
    _relay_thread.start()
    logger.info("Pricing outbox relay thread started")


def stop_outbox_relay() -> None:
    """Stop the pricing outbox relay thread."""
    global _relay_thread, _stop_event
    if _stop_event is not None:
        _stop_event.set()
    if _relay_thread is not None:
        _relay_thread.join(timeout=10)
        _relay_thread = None
    _stop_event = None
