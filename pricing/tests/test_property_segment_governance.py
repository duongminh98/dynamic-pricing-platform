"""Property tests 24, 19 - champion promotion rule and audit append-only.

Feature: dynamic-pricing-platform
Properties: 24, 19(audit)  (>=100 Hypothesis iterations each)
Validates: R37.4, R37.5, R37.9
"""
from __future__ import annotations

import datetime
import uuid

from hypothesis import given, settings, strategies as st, HealthCheck
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.database import Base, ModelVersion, ChampionAssignment, AuditTrail
from app.pricing_engine.governance import promote_champion


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
# Property 19 (audit): champion-change log is append-only (INSERT only)
# --------------------------------------------------------------------------
@given(
    champion_gini=st.floats(min_value=0.5, max_value=0.6, allow_nan=False),
)
@settings(max_examples=100, deadline=None,
          suppress_health_check=[HealthCheck.function_scoped_fixture])
def test_property19_audit_append_only(champion_gini):
    """A sequence of promotions only grows audit_trail; old rows persist untouched."""
    db = _fresh_session()
    try:
        champ_id = _seed_model(db, "health", champion_gini, True)
        _seed_champion(db, "health", champ_id)
        seen_event_ids = []
        for i in range(5):
            chall_id = _seed_model(db, "health", champion_gini + 0.01 * (i + 1), True)
            db.commit()
            promote_champion(db, "health", chall_id)
            rows = db.query(AuditTrail).filter(
                AuditTrail.event_type == "ChampionPromoted").all()
            seen_event_ids.extend(r.audit_id for r in rows)

        # All previously written champion-change audit rows are still present.
        all_rows = db.query(AuditTrail).filter(
            AuditTrail.event_type.in_(["ChampionPromoted", "CHAMPION_PROMOTE_REJECTED"])
        ).all()
        for eid in seen_event_ids:
            assert any(r.audit_id == eid for r in all_rows)
        assert len(all_rows) >= 5  # at least one audit row per promotion
    finally:
        db.close()
