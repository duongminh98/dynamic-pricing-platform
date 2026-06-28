import datetime
import json
import sys
import types
from unittest.mock import MagicMock, patch


def _entry(event_id="event-001", event_type="ChampionPromoted", status="NEW"):
    from app.database import EventOutbox

    return EventOutbox(
        event_id=event_id,
        event_type=event_type,
        routing_key=event_type,
        payload={"line": "car", "action": "promote"},
        status=status,
        created_at=datetime.datetime(2026, 6, 28, tzinfo=datetime.timezone.utc),
    )


def _db_with(entries):
    db = MagicMock()
    db.query.return_value.filter.return_value.order_by.return_value.limit.return_value.all.return_value = entries
    return db


def test_relay_once_publishes_new_event_and_marks_sent():
    from app.outbox_relay import relay_once

    fake_pika = types.SimpleNamespace(BasicProperties=MagicMock(side_effect=lambda **kwargs: types.SimpleNamespace(**kwargs)))
    entry = _entry()
    db = _db_with([entry])
    channel = MagicMock()
    connection = MagicMock()
    connection.channel.return_value = channel

    with patch.dict(sys.modules, {"pika": fake_pika}):
        published = relay_once(db=db, connection=connection)

    assert published == 1
    assert entry.status == "SENT"
    db.commit.assert_called_once()
    channel.basic_publish.assert_called_once()
    kwargs = channel.basic_publish.call_args.kwargs
    assert kwargs["exchange"] == "platform.events"
    assert kwargs["routing_key"] == "ChampionPromoted"
    assert json.loads(kwargs["body"].decode("utf-8")) == {"line": "car", "action": "promote"}
    assert kwargs["properties"].message_id == "event-001"
    assert kwargs["properties"].headers["X-Event-Type"] == "ChampionPromoted"


def test_relay_once_rolls_back_and_keeps_new_on_publish_error():
    from app.outbox_relay import relay_once

    fake_pika = types.SimpleNamespace(BasicProperties=MagicMock(side_effect=lambda **kwargs: types.SimpleNamespace(**kwargs)))
    entry = _entry(event_id="event-002", event_type="ChampionRolledBack")
    db = _db_with([entry])
    channel = MagicMock()
    channel.basic_publish.side_effect = RuntimeError("broker down")
    connection = MagicMock()
    connection.channel.return_value = channel

    with patch.dict(sys.modules, {"pika": fake_pika}):
        published = relay_once(db=db, connection=connection)

    assert published == 0
    assert entry.status == "NEW"
    db.rollback.assert_called_once()
    db.commit.assert_not_called()


def test_start_outbox_relay_is_idempotent():
    import app.outbox_relay as relay

    relay._relay_thread = MagicMock()
    relay._relay_thread.is_alive.return_value = True

    with patch("threading.Thread") as thread_cls:
        relay.start_outbox_relay()

    thread_cls.assert_not_called()
    relay._relay_thread = None
    relay._stop_event = None
