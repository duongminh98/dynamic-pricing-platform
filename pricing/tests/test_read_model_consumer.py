import datetime
from unittest.mock import MagicMock, patch


def test_policy_issued_upserts_policy_exposure():
    from app.consumers.read_model_consumer import upsert_policy_event

    mock_db = MagicMock()
    mock_db.query.return_value.filter.return_value.first.return_value = None
    mock_session_local = MagicMock(return_value=mock_db)

    payload = {
        "policy_id": "policy-1",
        "quote_id": "quote-1",
        "customer_id": "customer-1",
        "product_id": "CAR_BASIC",
        "line": "car",
        "exposure_segment_seq": 0,
        "segment_start": "2026-01-01T00:00:00+00:00",
        "segment_end": "2027-01-01T00:00:00+00:00",
        "earned_exposure_years": 0.9993,
        "coverage_amount_vnd": 500000000,
        "deductible_vnd": 1000000,
        "final_premium_vnd": 7000000,
        "risk_snapshot": {"age": 35, "vehicle_age": 4},
    }

    with patch("app.consumers.read_model_consumer.SessionLocal", mock_session_local):
        upsert_policy_event(payload, "PolicyIssued")

    added = mock_db.add.call_args[0][0]
    assert added.exposure_id == "policy-1:0"
    assert added.policy_id == "policy-1"
    assert added.quote_id == "quote-1"
    assert added.line == "car"
    assert added.coverage_amount_vnd == 500000000
    assert added.risk_snapshot["vehicle_age"] == 4
    mock_db.commit.assert_called_once()


def test_policy_cancel_closes_matching_exposures():
    from app.consumers.read_model_consumer import upsert_policy_event

    row = MagicMock()
    row.segment_start = datetime.datetime(2026, 1, 1, tzinfo=datetime.timezone.utc)
    row.segment_end = datetime.datetime(2027, 1, 1, tzinfo=datetime.timezone.utc)
    mock_db = MagicMock()
    mock_db.query.return_value.filter.return_value.all.return_value = [row]
    mock_session_local = MagicMock(return_value=mock_db)

    with patch("app.consumers.read_model_consumer.SessionLocal", mock_session_local):
        upsert_policy_event({"policy_id": "policy-1", "cancel_date": "2026-07-01T00:00:00+00:00"}, "PolicyCancelled")

    assert row.status == "cancelled"
    assert row.segment_end.isoformat() == "2026-07-01T00:00:00+00:00"
    assert row.earned_exposure_years > 0
    mock_db.commit.assert_called_once()


def test_customer_profile_event_upserts_latest_read_model():
    from app.consumers.read_model_consumer import upsert_customer_risk_profile

    mock_db = MagicMock()
    mock_db.query.return_value.filter.return_value.first.return_value = None
    mock_session_local = MagicMock(return_value=mock_db)

    payload = {
        "event_id": "event-1",
        "customer_id": "customer-1",
        "profile_version": 2,
        "effective_at": "2026-06-30T00:00:00+00:00",
        "common_risk_attributes": {"age": 31},
        "line_risk_attributes": {"health": {"smoker": False}},
    }

    with patch("app.consumers.read_model_consumer.SessionLocal", mock_session_local):
        upsert_customer_risk_profile(payload)

    added = mock_db.add.call_args[0][0]
    assert added.customer_id == "customer-1"
    assert added.profile_version == 2
    assert added.common_risk_attributes["age"] == 31
    assert added.line_risk_attributes["health"]["smoker"] is False
    mock_db.commit.assert_called_once()

def test_geo_risk_event_routes_to_product_read_model():
    from app.consumers.read_model_consumer import upsert_product_event

    mock_db = MagicMock()
    mock_session_local = MagicMock(return_value=mock_db)
    payload = {"version_id": "geo-v1", "rows": []}

    with patch("app.consumers.read_model_consumer.SessionLocal", mock_session_local), \
         patch("app.consumers.read_model_consumer.upsert_geo_risk_version_activated") as upsert:
        upsert_product_event(payload, "GeoRiskVersionActivated")

    upsert.assert_called_once_with(mock_db, payload)
    mock_db.commit.assert_called_once()


def test_cost_index_event_routes_to_product_read_model():
    from app.consumers.read_model_consumer import upsert_product_event

    mock_db = MagicMock()
    mock_session_local = MagicMock(return_value=mock_db)
    payload = {"version_id": "cost-v1", "rows": []}

    with patch("app.consumers.read_model_consumer.SessionLocal", mock_session_local), \
         patch("app.consumers.read_model_consumer.upsert_cost_index_version_activated") as upsert:
        upsert_product_event(payload, "CostIndexVersionActivated")

    upsert.assert_called_once_with(mock_db, payload)
    mock_db.commit.assert_called_once()
