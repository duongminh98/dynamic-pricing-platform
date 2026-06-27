"""Tests for ClaimSettled consumer and claim_outcome upsert (T1-T5).

Tests the pure upsert_claim_outcome function with synthetic payloads,
verifying idempotency (re-emit updates existing row) and correct field mapping.
"""
import pytest
import datetime
import json
from unittest.mock import MagicMock, patch, ANY


class TestUpsertClaimOutcome:
    """T1-T3: Consumer upsert logic with synthetic ClaimSettled payloads."""

    def test_t1_upsert_new_outcome(self):
        from app.consumers.claim_settled_consumer import upsert_claim_outcome

        mock_existing = None
        mock_db = MagicMock()
        mock_db.query.return_value.filter.return_value.first.return_value = mock_existing
        mock_session_local = MagicMock(return_value=mock_db)

        payload = {
            "claim_id": "claim-001",
            "policy_id": "pol-001",
            "quote_id": "quote-001",
            "line": "car",
            "paid_amount_vnd": 5000000,
            "settled_at": "2026-06-28T10:00:00+00:00",
        }

        with patch("app.consumers.claim_settled_consumer.SessionLocal", mock_session_local):
            upsert_claim_outcome(payload)

        mock_db.add.assert_called_once()
        added_obj = mock_db.add.call_args[0][0]
        assert added_obj.claim_id == "claim-001"
        assert added_obj.quote_id == "quote-001"
        assert added_obj.line == "car"
        assert added_obj.actual_loss_vnd == 5000000
        mock_db.commit.assert_called_once()

    def test_t2_upsert_idempotent_updates_existing(self):
        from app.consumers.claim_settled_consumer import upsert_claim_outcome

        mock_existing = MagicMock()
        mock_existing.claim_id = "claim-001"
        mock_existing.quote_id = "quote-001"
        mock_existing.actual_loss_vnd = 5000000
        mock_db = MagicMock()
        mock_db.query.return_value.filter.return_value.first.return_value = mock_existing
        mock_session_local = MagicMock(return_value=mock_db)

        payload = {
            "claim_id": "claim-001",
            "policy_id": "pol-001",
            "quote_id": "quote-001",
            "line": "car",
            "paid_amount_vnd": 3000000,
            "settled_at": "2026-06-28T12:00:00+00:00",
        }

        with patch("app.consumers.claim_settled_consumer.SessionLocal", mock_session_local):
            upsert_claim_outcome(payload)

        mock_db.add.assert_not_called()
        assert mock_existing.actual_loss_vnd == 3000000
        mock_db.commit.assert_called_once()

    def test_t3_skips_missing_claim_id(self):
        from app.consumers.claim_settled_consumer import upsert_claim_outcome

        mock_session_local = MagicMock()
        with patch("app.consumers.claim_settled_consumer.SessionLocal", mock_session_local):
            upsert_claim_outcome({"policy_id": "pol-001"})

        mock_session_local.assert_not_called()

    def test_t4_rollback_on_exception(self):
        from app.consumers.claim_settled_consumer import upsert_claim_outcome

        mock_db = MagicMock()
        mock_db.query.side_effect = Exception("DB error")
        mock_session_local = MagicMock(return_value=mock_db)

        payload = {
            "claim_id": "claim-002",
            "paid_amount_vnd": 1000000,
            "settled_at": "2026-06-28T10:00:00+00:00",
        }

        with patch("app.consumers.claim_settled_consumer.SessionLocal", mock_session_local):
            with pytest.raises(Exception):
                upsert_claim_outcome(payload)

        mock_db.rollback.assert_called_once()
        mock_db.close.assert_called_once()
