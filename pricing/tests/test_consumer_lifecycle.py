"""Lifecycle tests for the pricing consumers: start/stop thread management.

These cover start_consumer / stop_consumer idempotency and the stop path
without touching a real RabbitMQ broker (the consume loop is not exercised).
"""
from __future__ import annotations

from unittest.mock import MagicMock, patch


# ── ClaimSettled consumer ──

def test_claim_settled_start_consumer_idempotent_when_alive():
    import app.consumers.claim_settled_consumer as c

    c._consumer_thread = MagicMock()
    c._consumer_thread.is_alive.return_value = True
    try:
        with patch("threading.Thread") as thread_cls:
            c.start_consumer()
        thread_cls.assert_not_called()
    finally:
        c._consumer_thread = None
        c._stop_event = None


def test_claim_settled_start_consumer_spawns_thread():
    import app.consumers.claim_settled_consumer as c

    c._consumer_thread = None
    c._stop_event = None
    fake_thread = MagicMock()
    try:
        with patch("threading.Thread", return_value=fake_thread) as thread_cls:
            c.start_consumer()
        thread_cls.assert_called_once()
        fake_thread.start.assert_called_once()
        assert c._stop_event is not None
    finally:
        c._consumer_thread = None
        c._stop_event = None


def test_claim_settled_stop_consumer_signals_and_joins():
    import app.consumers.claim_settled_consumer as c

    stop_event = MagicMock()
    thread = MagicMock()
    c._stop_event = stop_event
    c._consumer_thread = thread

    c.stop_consumer()

    stop_event.set.assert_called_once()
    thread.join.assert_called_once()
    assert c._consumer_thread is None
    assert c._stop_event is None


def test_claim_settled_stop_consumer_noop_when_not_started():
    import app.consumers.claim_settled_consumer as c

    c._stop_event = None
    c._consumer_thread = None
    c.stop_consumer()  # must not raise
    assert c._consumer_thread is None


# ── Read-model consumer ──

def test_read_model_start_consumer_idempotent_when_alive():
    import app.consumers.read_model_consumer as c

    c._consumer_thread = MagicMock()
    c._consumer_thread.is_alive.return_value = True
    try:
        with patch("threading.Thread") as thread_cls:
            c.start_consumer()
        thread_cls.assert_not_called()
    finally:
        c._consumer_thread = None
        c._stop_event = None


def test_read_model_start_consumer_spawns_thread():
    import app.consumers.read_model_consumer as c

    c._consumer_thread = None
    c._stop_event = None
    fake_thread = MagicMock()
    try:
        with patch("threading.Thread", return_value=fake_thread) as thread_cls:
            c.start_consumer()
        thread_cls.assert_called_once()
        fake_thread.start.assert_called_once()
        assert c._stop_event is not None
    finally:
        c._consumer_thread = None
        c._stop_event = None


def test_read_model_stop_consumer_signals_and_joins():
    import app.consumers.read_model_consumer as c

    stop_event = MagicMock()
    thread = MagicMock()
    c._stop_event = stop_event
    c._consumer_thread = thread

    c.stop_consumer()

    stop_event.set.assert_called_once()
    thread.join.assert_called_once()
    assert c._consumer_thread is None
    assert c._stop_event is None


def test_read_model_stop_consumer_noop_when_not_started():
    import app.consumers.read_model_consumer as c

    c._stop_event = None
    c._consumer_thread = None
    c.stop_consumer()  # must not raise
    assert c._consumer_thread is None


# ── Outbox relay stop path (start idempotency already covered elsewhere) ──

def test_outbox_relay_stop_signals_and_joins():
    import app.outbox_relay as relay

    stop_event = MagicMock()
    thread = MagicMock()
    relay._stop_event = stop_event
    relay._relay_thread = thread

    relay.stop_outbox_relay()

    stop_event.set.assert_called_once()
    thread.join.assert_called_once()
    assert relay._relay_thread is None
    assert relay._stop_event is None
