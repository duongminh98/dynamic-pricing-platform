"""Property tests 6, 7, 17, 20, 22 — validation, SHAP structure,
point-in-time no-leakage, audit round-trip, model selection.

Feature: dynamic-pricing-platform
Properties: 6, 7, 17, 20, 22  (>=100 Hypothesis iterations each)
Validates: R1.2-R1.4, R2.6, R2.9, R2.17, R4.5, R5.1-R5.6, R11.5,
           R12.1, R12.3, R12.5, R12.6, R13.2, R29.1, R29.3, R29.4, R35.1, R35.5
"""
from __future__ import annotations

import json

import pytest
from hypothesis import given, settings, strategies as st, HealthCheck
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.database import Base
from app.pricing_engine.engine import quote
from app.pricing_engine.loader import required_columns, LEAKAGE_COLS, get_line_for_product
from app.pricing_engine.features import build_features
from app.pricing_engine.selection import select_model
from common.errors import ErrorCode, ServiceException

from tests.conftest import PROVINCES, PRODUCTS_BY_LINE, ALL_LINES, make_profile

LEAKAGE = LEAKAGE_COLS | {"freq_rate", "sev_base", "final_premium_vnd",
                          "claim_count", "claim_flag", "incurred_amount",
                          "paid_amount", "occurrence_date", "report_date"}


# --------------------------------------------------------------------------
# Property 6: input validation rejects bad/missing features, keeps profile
# --------------------------------------------------------------------------
@given(
    bad_age=st.one_of(st.integers(min_value=-100, max_value=0),
                      st.integers(min_value=120, max_value=200)),
)
@settings(max_examples=100, deadline=None)
def test_property6_rejects_out_of_range_age(bad_age):
    """An out-of-range age is rejected with a descriptive error."""
    prof = make_profile("health", age=bad_age,
                        line_attributes={"smoker": False, "height_cm": 170, "weight_kg": 65})
    with pytest.raises(ServiceException) as exc:
        quote(None, "HEALTH_BASIC", prof)
    assert exc.value.error_code in (ErrorCode.MISSING_FEATURES,
                                    ErrorCode.PROFILE_FIELD_OUT_OF_RANGE,
                                    ErrorCode.BAD_REQUEST)


@given(
    province=st.sampled_from(PROVINCES),
    missing_smoker=st.booleans(),
)
@settings(max_examples=100, deadline=None)
def test_property6_valid_profile_accepted(province, missing_smoker):
    """A valid profile is accepted and returns a non-negative VND premium."""
    attrs = {"height_cm": 170, "weight_kg": 65}
    if not missing_smoker:
        attrs["smoker"] = False
    prof = make_profile("health", province=province, line_attributes=attrs)
    r = quote(None, "HEALTH_BASIC", prof)
    assert r["pure_premium_vnd"] >= 0
    assert r["currency"] == "VND"


# --------------------------------------------------------------------------
# Property 7: SHAP explanation structure (>=3 items, direction, magnitude)
# --------------------------------------------------------------------------
@given(
    age=st.integers(min_value=18, max_value=80),
    province=st.sampled_from(PROVINCES),
    smoker=st.booleans(),
)
@settings(max_examples=100, deadline=None)
def test_property7_shap_structure(age, province, smoker):
    prof = make_profile("health", age=age, province=province,
                        line_attributes={"smoker": smoker, "height_cm": 170, "weight_kg": 65})
    r = quote(None, "HEALTH_BASIC", prof)
    ex = r["explanation"]
    if ex["available"]:
        items = ex["items"]
        assert len(items) >= 3
        for it in items:
            assert "feature" in it and "label_vi" in it
            assert it["direction"] in ("tang", "giam")
            assert it["magnitude"] >= 0
    else:
        # graceful degradation: quote still valid
        assert r["pure_premium_vnd"] >= 0


# --------------------------------------------------------------------------
# Property 17: point-in-time claim history, no future leakage (BR-16)
# --------------------------------------------------------------------------
@given(line=st.sampled_from(ALL_LINES))
@settings(max_examples=100, deadline=None)
def test_property17_no_leakage_in_feature_set(line):
    pid = PRODUCTS_BY_LINE[line][0]
    cols = required_columns(line)
    # The feature set used for pricing must contain NO leakage/target columns.
    for col in cols:
        assert col not in LEAKAGE, f"{line}: leakage column {col} present in features"
    # Prior-claim fields are point-in-time (_prior suffix only).
    prior_cols = [c for c in cols if "prior" in c]
    for c in prior_cols:
        assert c.endswith("_prior"), f"{line}: non-prior history field {c}"


@given(line=st.sampled_from(ALL_LINES))
@settings(max_examples=100, deadline=None)
def test_property17_no_claim_defaults_when_empty(line):
    """When a profile has no prior claims, the feature set uses safe defaults."""
    pid = PRODUCTS_BY_LINE[line][0]
    cols = required_columns(line)
    prof = make_profile(line)
    df = build_features(line, pid, prof, cols)
    for c in ("claim_count_36m_prior", "claim_count_12m_prior"):
        if c in df.columns:
            assert int(df.iloc[0][c]) == 0
    if "days_since_last_claim_prior" in df.columns:
        assert int(df.iloc[0]["days_since_last_claim_prior"]) == 9999


# --------------------------------------------------------------------------
# Property 20: audit round-trip (bonus - per-quote audit gated, R35)
# --------------------------------------------------------------------------
@given(
    age=st.integers(min_value=18, max_value=80),
    province=st.sampled_from(PROVINCES),
)
@settings(max_examples=100, deadline=None,
          suppress_health_check=[HealthCheck.function_scoped_fixture])
def test_property20_audit_roundtrip(age, province):
    """Bonus (R35): per-quote audit is gated behind PRICING_BONUS_QUOTE_AUDIT_ENABLED.
    This test enables the flag to verify the round-trip when the bonus is on."""
    from app.database import AuditTrail
    import app.config as config
    saved = config.QUOTE_AUDIT_ENABLED
    config.QUOTE_AUDIT_ENABLED = True
    engine = create_engine("sqlite:///:memory:")
    Base.metadata.create_all(engine)
    Session = sessionmaker(bind=engine)
    db_session = Session()
    try:
        prof = make_profile("health", age=age, province=province,
                            line_attributes={"smoker": False, "height_cm": 170, "weight_kg": 65})
        r = quote(db_session, "HEALTH_BASIC", prof)
        db_session.commit()
        audit = db_session.query(AuditTrail).filter(
            AuditTrail.quote_id == r["quote_id"]).first()
        assert audit is not None
        assert audit.model_version == r["model_version"]
        assert audit.rate_version_id == r["rate_version"]
        fs = audit.feature_set if isinstance(audit.feature_set, dict) else json.loads(
            audit.feature_set)
        assert fs.get("age") == age
        assert fs.get("coverage_amount_vnd") is not None
    finally:
        db_session.close()
        config.QUOTE_AUDIT_ENABLED = saved


# --------------------------------------------------------------------------
# Property 22: model selection (champion / challenger)
# --------------------------------------------------------------------------
@given(line=st.sampled_from(["health", "car", "travel"]))
@settings(max_examples=100, deadline=None)
def test_property22_none_uses_champion(line):
    sel = select_model(line, None)
    assert sel["model_version"]  # champion resolved
    assert sel["model"] is not None


@given(line=st.sampled_from(ALL_LINES))
@settings(max_examples=100, deadline=None)
def test_property22_unconfigured_challenger_rejected(line):
    """A challenger not configured for the line is rejected (no silent fallback)."""
    with pytest.raises(ServiceException) as exc:
        select_model(line, "nonexistent-version-0000")
    assert exc.value.error_code == ErrorCode.CHALLENGER_NOT_CONFIGURED


def test_property22_missing_champion_rejected():
    """A line with no champion config is rejected with MISSING_CHAMPION."""
    import app.pricing_engine.loader as loader
    original = dict(loader.champion_config.get("champion_by_line", {}))
    try:
        loader.champion_config["champion_by_line"] = {
            k: v for k, v in original.items() if k != "health"
        }
        with pytest.raises(ServiceException) as exc:
            select_model("health", None)
        assert exc.value.error_code == ErrorCode.MISSING_CHAMPION
    finally:
        loader.champion_config["champion_by_line"] = original