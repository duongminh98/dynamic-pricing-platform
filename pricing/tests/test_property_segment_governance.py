"""Property tests 23, 24, 19 — risk segment, champion promotion, audit append-only.

Feature: dynamic-pricing-platform
Properties: 23, 24, 19(audit)  (>=100 Hypothesis iterations each)
Validates: R14.1, R14.2, R14.4, R14.5, R35.3, R37.4, R37.5, R37.9
"""
from __future__ import annotations

import datetime
import uuid

import pytest
from hypothesis import given, settings, strategies as st, HealthCheck
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.database import Base, ModelVersion, ChampionAssignment, AuditTrail
from app.pricing_engine.engine import quote
from app.pricing_engine.segment import get_risk_segment, SEGMENTS
from app.pricing_engine.governance import promote_champion, rollback_champion

from tests.conftest import PROVINCES, make_profile


# --------------------------------------------------------------------------
# Property 23: risk segment is unique and deterministic
# --------------------------------------------------------------------------
@given(
    age=st.integers(min_value=18, max_value=80),
    province=st.sampled_from(PROVINCES),
    claims=st.integers(min_value=0, max_value=10),
    severity=st.floats(min_value=0.0, max_value=1.0, allow_nan=False),
)
@settings(max_examples=100, deadline=None)
def test_property23_segment_valid_and_deterministic(age, province, claims, severity):
    prof = {"age": age, "province": province,
            "claim_count_36m_prior": claims,
            "claim_severity_score_prior": severity}
    seg1 = get_risk_segment("health", prof)
    seg2 = get_risk_segment("health", prof)
    assert seg1 in SEGMENTS
    assert seg1 == seg2  # deterministic


@given(
    age=st.integers(min_value=18, max_value=80),
    province=st.sampled_from(PROVINCES),
)
@settings(max_examples=100, deadline=None)
def test_property23_segment_in_quote(age, province):
    prof = make_profile("health", age=age, province=province,
                        line_attributes={"smoker": False, "height_cm": 170, "weight_kg": 65})
    r = quote(None, "HEALTH_BASIC", prof)
    assert r["risk_segment"] in SEGMENTS


# --------------------------------------------------------------------------
# Helpers + fixtures for DB-backed governance tests
# --------------------------------------------------------------------------
def _fresh_session():
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine)
    return Session()


def _seed_model(db, line, gini, monotonic, version_id=None):
    version_id = version_id or str(uuid.uuid4())
    db.add(ModelVersion(
        model_version_id=version_id, line=line, algorithm="LightGBM",
        gini=gini, rmse=0.0, mae=0.0, deviance=0.0,
        trained_at=datetime.datetime.now(datetime.timezone.utc),
        dataset_desc="synthetic_real", monotonic_applied=monotonic))
    return version_id


def _seed_champion(db, line, version_id):
    db.add(ChampionAssignment(assignment_id=str(uuid.uuid4()), line=line, model_version_id=version_id, is_current=True))


# --------------------------------------------------------------------------
# Property 24: controlled champion promotion (BR-23)
# --------------------------------------------------------------------------
@given(
    champion_gini=st.floats(min_value=0.4, max_value=0.7, allow_nan=False),
    challenger_gini=st.floats(min_value=0.4, max_value=0.8, allow_nan=False),
    challenger_monotonic=st.booleans(),
)
@settings(max_examples=100, deadline=None,
          suppress_health_check=[HealthCheck.function_scoped_fixture])
def test_property24_promotion_rule(champion_gini, challenger_gini, challenger_monotonic):
    """Promote iff challenger Gini > champion Gini AND monotonic applied."""
    db = _fresh_session()
    try:
        champ_id = _seed_model(db, "health", champion_gini, True)
        _seed_champion(db, "health", champ_id)
        chall_id = _seed_model(db, "health", challenger_gini, challenger_monotonic)
        db.commit()

        result = promote_champion(db, "health", chall_id)
        should_promote = (challenger_monotonic and challenger_gini > champion_gini)
        assert result["promoted"] is should_promote

        # An audit trail entry exists for the decision either way.
        events = db.query(AuditTrail).filter(
            AuditTrail.event_type.in_(["ChampionPromoted", "CHAMPION_PROMOTE_REJECTED"])
        ).all()
        assert len(events) >= 1
    finally:
        db.close()


@given(
    champion_gini=st.floats(min_value=0.5, max_value=0.6, allow_nan=False),
)
@settings(max_examples=100, deadline=None,
          suppress_health_check=[HealthCheck.function_scoped_fixture])
def test_property24_promotion_records_audit(champion_gini):
    """A successful promotion writes an append-only ChampionPromoted audit row."""
    db = _fresh_session()
    try:
        champ_id = _seed_model(db, "health", champion_gini, True)
        _seed_champion(db, "health", champ_id)
        chall_id = _seed_model(db, "health", champion_gini + 0.1, True)
        db.commit()
        before = db.query(AuditTrail).count()
        promote_champion(db, "health", chall_id)
        after = db.query(AuditTrail).count()
        assert after == before + 1  # append-only: only INSERT, count grows by 1
        promoted = db.query(AuditTrail).filter(
            AuditTrail.event_type == "ChampionPromoted").first()
        assert promoted is not None
    finally:
        db.close()


# --------------------------------------------------------------------------
# Property 19 (audit): audit_trail is append-only (INSERT only, no UPDATE/DELETE)
# --------------------------------------------------------------------------
@given(
    age=st.integers(min_value=18, max_value=80),
    province=st.sampled_from(PROVINCES),
)
@settings(max_examples=100, deadline=None,
          suppress_health_check=[HealthCheck.function_scoped_fixture])
def test_property19_audit_append_only(age, province):
    """A sequence of quotes only grows audit_trail; old rows persist untouched."""
    db = _fresh_session()
    try:
        seen_ids = []
        for i in range(5):
            prof = make_profile("health", age=age, province=province,
                                line_attributes={"smoker": bool(i % 2),
                                                 "height_cm": 170, "weight_kg": 65})
            r = quote(db, "HEALTH_BASIC", prof)
            db.commit()
            seen_ids.append(r["quote_id"])

        rows = db.query(AuditTrail).filter(
            AuditTrail.quote_id.in_(seen_ids)).all()
        # Every quote produced exactly one audit row, and all are still present.
        assert len(rows) == len(seen_ids)
        for row in rows:
            assert row.quote_id in seen_ids
            assert row.created_at is not None
    finally:
        db.close()