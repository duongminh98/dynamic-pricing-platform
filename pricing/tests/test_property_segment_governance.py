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

from app.database import Base, ModelVersion, ChampionAssignment, AuditTrail, EventOutbox
from app.pricing_engine.governance import promote_champion, rollback_champion


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
    db.add(ChampionAssignment(assignment_id=str(uuid.uuid4()), line=line, model_version_id=version_id, is_current=True,
                              created_at=datetime.datetime.now(datetime.timezone.utc)))


def _seed_model_algo(db, line, gini, monotonic, algorithm, version_id=None):
    """Seed a ModelVersion with an explicit algorithm (for exemption tests)."""
    version_id = version_id or str(uuid.uuid4())
    db.add(ModelVersion(
        model_version_id=version_id, line=line, algorithm=algorithm,
        gini=gini, rmse=0.0, mae=0.0, deviance=0.0,
        trained_at=datetime.datetime.now(datetime.timezone.utc),
        dataset_desc="synthetic_real", monotonic_applied=monotonic))
    return version_id


# --------------------------------------------------------------------------
# BR-19 travel exemption (task 20.8b): a GLM champion on a monotonic-exempt
# line may promote on Gini alone; tree/LightGBM candidates still need monotonic.
# --------------------------------------------------------------------------
def test_travel_glm_promotes_without_monotonic_exempt():
    db = _fresh_session()
    try:
        champ_id = _seed_model_algo(db, "travel", 0.50, monotonic=False, algorithm="GLM")
        _seed_champion(db, "travel", champ_id)
        chall_id = _seed_model_algo(db, "travel", 0.60, monotonic=False, algorithm="GLM")
        db.commit()
        result = promote_champion(db, "travel", chall_id)
        # Exempt GLM line: monotonic_applied=False is allowed; Gini improved -> promote.
        assert result["promoted"] is True
        assert result["champion"] == chall_id
    finally:
        db.close()


def test_travel_lightgbm_candidate_still_requires_monotonic():
    db = _fresh_session()
    try:
        champ_id = _seed_model_algo(db, "travel", 0.50, monotonic=False, algorithm="GLM")
        _seed_champion(db, "travel", champ_id)
        # A tree/LightGBM candidate is NOT exempt even on an exempt line.
        chall_id = _seed_model_algo(db, "travel", 0.70, monotonic=False, algorithm="LightGBM")
        db.commit()
        result = promote_champion(db, "travel", chall_id)
        assert result["promoted"] is False
        assert result["reason"] == "MONOTONIC_NOT_APPLIED"
    finally:
        db.close()


def test_non_exempt_glm_line_still_requires_monotonic():
    db = _fresh_session()
    try:
        # health is NOT in the exemption set: even a GLM must satisfy monotonic.
        champ_id = _seed_model_algo(db, "health", 0.50, monotonic=True, algorithm="GLM")
        _seed_champion(db, "health", champ_id)
        chall_id = _seed_model_algo(db, "health", 0.70, monotonic=False, algorithm="GLM")
        db.commit()
        result = promote_champion(db, "health", chall_id)
        assert result["promoted"] is False
        assert result["reason"] == "MONOTONIC_NOT_APPLIED"
    finally:
        db.close()


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
            AuditTrail.event_type.in_(["CHAMPION_CHANGE", "CHAMPION_PROMOTE_REJECTED"])
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
            AuditTrail.event_type == "CHAMPION_CHANGE").first()
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
                AuditTrail.event_type == "CHAMPION_CHANGE").all()
            seen_event_ids.extend(r.audit_id for r in rows)

        # All previously written champion-change audit rows are still present.
        all_rows = db.query(AuditTrail).filter(
            AuditTrail.event_type.in_(["CHAMPION_CHANGE", "CHAMPION_PROMOTE_REJECTED"])
        ).all()
        for eid in seen_event_ids:
            assert any(r.audit_id == eid for r in all_rows)
        assert len(all_rows) >= 5  # at least one audit row per promotion
    finally:
        db.close()


def test_rollback_is_append_only_and_publishes_event():
    """Rollback appends a new current assignment (no UPDATE-in-place destruction)
    and emits a ChampionRolledBack outbox event (R37.5/R37.8/R37.9)."""
    db = _fresh_session()
    try:
        champ_id = _seed_model(db, "health", 0.50, True)
        _seed_champion(db, "health", champ_id)
        chall_id = _seed_model(db, "health", 0.65, True)
        db.commit()
        promote_champion(db, "health", chall_id)
        assignments_before = db.query(ChampionAssignment).filter(
            ChampionAssignment.line == "health").count()

        result = rollback_champion(db, "health")
        assert result["rolled_back"] is True
        assert result["champion"] == champ_id

        # Append-only: a new assignment row was inserted (count grows), none deleted.
        assignments_after = db.query(ChampionAssignment).filter(
            ChampionAssignment.line == "health").count()
        assert assignments_after == assignments_before + 1

        # Exactly one current assignment, pointing back to the original champion.
        current = db.query(ChampionAssignment).filter(
            ChampionAssignment.line == "health",
            ChampionAssignment.is_current.is_(True)).all()
        assert len(current) == 1
        assert current[0].model_version_id == champ_id

        # An outbox event was published for the rollback.
        events = db.query(EventOutbox).filter(
            EventOutbox.event_type == "ChampionRolledBack").all()
        assert len(events) == 1
        assert events[0].routing_key == "ChampionRolledBack"
    finally:
        db.close()


def test_promote_publishes_outbox_event():
    db = _fresh_session()
    try:
        champ_id = _seed_model(db, "car", 0.50, True)
        _seed_champion(db, "car", champ_id)
        chall_id = _seed_model(db, "car", 0.70, True)
        db.commit()
        promote_champion(db, "car", chall_id)
        events = db.query(EventOutbox).filter(
            EventOutbox.event_type == "ChampionPromoted").all()
        assert len(events) == 1
        assert events[0].payload["new"] == chall_id
    finally:
        db.close()
