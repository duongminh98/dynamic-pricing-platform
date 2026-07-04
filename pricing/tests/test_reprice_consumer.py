"""Tests for read_model_consumer.process_reprice_requested.

Covers the success path (Quote + QuoteFeatureSnapshot + RepriceCompleted outbox),
the missing-required-fields skip, and the failure path (RepriceCompleted carries
a failure_reason when quote() raises). Uses an in-memory SQLite shared across the
consumer's own SessionLocal() so written rows can be asserted; the pricing engine
is stubbed so no model artifacts are required.
"""
from __future__ import annotations

import datetime
from unittest.mock import MagicMock, patch

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.database import Base, EventOutbox, Quote, QuoteFeatureSnapshot


def _shared_sessionmaker():
    engine = create_engine(
        "sqlite://",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine)


def _quote_result():
    now = datetime.datetime.now(datetime.timezone.utc)
    return {
        "quote_id": "reprice-quote-1",
        "line": "health",
        "product_id": "HEALTH_BASIC",
        "trip_duration_days": None,
        "coverage_amount_vnd": 100_000_000,
        "deductible_vnd": 0,
        "pure_premium_vnd": 500_000,
        "final_premium_vnd": 610_000,
        "currency": "VND",
        "expires_at": (now + datetime.timedelta(days=7)).isoformat(),
        "created_at": now.isoformat(),
        "explanation": {"available": False, "items": []},
        "model_version": "v1.0",
        "rate_version": "rv-test",
    }


def test_reprice_success_writes_quote_and_completed_event():
    from app.consumers import read_model_consumer

    Session = _shared_sessionmaker()
    payload = {
        "pricing_request_id": "req-1",
        "workflow": "ENDORSEMENT",
        "customer_id": "customer-1",
        "policy_id": "policy-1",
        "aggregate_id": "agg-1",
        "product_id": "HEALTH_BASIC",
        "profile": {"age": 30},
    }

    with patch("app.consumers.read_model_consumer.SessionLocal", Session), \
         patch("app.pricing_engine.engine.quote", return_value=_quote_result()), \
         patch("app.pricing_engine.features.feature_set_for_audit", return_value={"age": 30}), \
         patch("app.pricing_engine.loader.required_columns", return_value=["age"]):
        read_model_consumer.process_reprice_requested(payload)

    db = Session()
    try:
        quote_row = db.query(Quote).filter(Quote.quote_id == "reprice-quote-1").first()
        assert quote_row is not None
        assert quote_row.customer_id == "customer-1"
        assert quote_row.final_premium_vnd == 610_000

        snapshot = db.query(QuoteFeatureSnapshot).filter(
            QuoteFeatureSnapshot.quote_id == "reprice-quote-1"
        ).first()
        assert snapshot is not None
        assert snapshot.feature_set == {"age": 30}

        completed = db.query(EventOutbox).filter(
            EventOutbox.event_type == "RepriceCompleted"
        ).all()
        assert len(completed) == 1
        assert completed[0].payload["pricing_request_id"] == "req-1"
        assert completed[0].payload["quote_id"] == "reprice-quote-1"
        assert "failure_reason" not in completed[0].payload
    finally:
        db.close()


def test_reprice_missing_required_fields_skips():
    from app.consumers import read_model_consumer

    mock_session = MagicMock()
    with patch("app.consumers.read_model_consumer.SessionLocal", mock_session):
        # Missing product_id → skip before opening a session.
        read_model_consumer.process_reprice_requested({"pricing_request_id": "req-2"})
        mock_session.assert_not_called()


def test_reprice_defaults_customer_to_internal():
    from app.consumers import read_model_consumer

    Session = _shared_sessionmaker()
    payload = {
        "pricing_request_id": "req-3",
        "product_id": "HEALTH_BASIC",
        "profile": {"age": 40},
    }
    with patch("app.consumers.read_model_consumer.SessionLocal", Session), \
         patch("app.pricing_engine.engine.quote", return_value=_quote_result()), \
         patch("app.pricing_engine.features.feature_set_for_audit", return_value={"age": 40}), \
         patch("app.pricing_engine.loader.required_columns", return_value=["age"]):
        read_model_consumer.process_reprice_requested(payload)

    db = Session()
    try:
        quote_row = db.query(Quote).first()
        assert quote_row.customer_id == "internal"
    finally:
        db.close()


def test_reprice_failure_publishes_failure_reason():
    from app.consumers import read_model_consumer

    Session = _shared_sessionmaker()
    payload = {
        "pricing_request_id": "req-4",
        "workflow": "RENEWAL",
        "customer_id": "customer-9",
        "policy_id": "policy-9",
        "product_id": "HEALTH_BASIC",
        "profile": {"age": 55},
    }
    with patch("app.consumers.read_model_consumer.SessionLocal", Session), \
         patch("app.pricing_engine.engine.quote", side_effect=RuntimeError("model boom")):
        read_model_consumer.process_reprice_requested(payload)

    db = Session()
    try:
        assert db.query(Quote).count() == 0
        completed = db.query(EventOutbox).filter(
            EventOutbox.event_type == "RepriceCompleted"
        ).all()
        assert len(completed) == 1
        assert completed[0].payload["failure_reason"].startswith("model boom")
        assert completed[0].payload["pricing_request_id"] == "req-4"
    finally:
        db.close()
