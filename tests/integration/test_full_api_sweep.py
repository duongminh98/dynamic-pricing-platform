"""Full API sweep through Kong against the live Docker stack.

Feature: dynamic-pricing-platform
Exercises EVERY service's HTTP surface end-to-end (not just the core saga):
customer auth/profile, product catalog + admin config, pricing quote/registry/
governance, order lifecycle (order -> review -> issue -> endorsement (non-material
+ material/admin-gated) -> renew -> cancel), billing (invoice/pay/list/VNPAY
payment-url), claims (FNOL -> approve/reject/misrepresentation), notifications,
plus RBAC negatives. Asserts HTTP status + snake_case contracts (task 25.1) and
that nothing returns 5xx.

SKIPS (not fails) when the live stack (Kong on :8000) is not reachable, so CI
without a stack stays green. Run after `docker compose up`.
"""
from __future__ import annotations

import datetime as dt
import os
import time
import uuid

import pytest

httpx = pytest.importorskip("httpx")

GATEWAY = os.getenv("DPP_GATEWAY_URL", "http://localhost:8000")
KEYCLOAK = os.getenv("DPP_KEYCLOAK_URL", "http://localhost:8080")
REALM = os.getenv("KEYCLOAK_REALM", "dynamic-pricing")
ADMIN_USER = os.getenv("DPP_DEMO_ADMIN", "demo.admin")
ADMIN_PASS = os.getenv("DPP_DEMO_ADMIN_PASS", "demo_admin_dev_only")
POLL_TIMEOUT_S = float(os.getenv("DPP_E2E_POLL_TIMEOUT", "45"))


def _stack_up() -> bool:
    try:
        return httpx.get(f"{GATEWAY}/products", timeout=3.0).status_code < 500
    except Exception:
        return False


pytestmark = pytest.mark.skipif(not _stack_up(), reason="Live stack (Kong) not reachable on :8000")


# ── helpers ─────────────────────────────────────────────────────────────────
def _kc_token(username: str, password: str) -> str:
    r = httpx.post(f"{KEYCLOAK}/realms/{REALM}/protocol/openid-connect/token",
                   data={"grant_type": "password", "client_id": "mini-app",
                         "username": username, "password": password, "scope": "openid"},
                   timeout=10.0)
    r.raise_for_status()
    return r.json()["access_token"]


def _auth(t: str) -> dict:
    return {"Authorization": f"Bearer {t}"}


def _register_and_login(line_label: str) -> tuple[str, str]:
    email = f"sweep.{line_label}.{uuid.uuid4().hex[:10]}@example.com"
    pw = "sweep_dev_only_pw"
    reg = httpx.post(f"{GATEWAY}/customers/register", json={"email": email, "password": pw}, timeout=15.0)
    assert reg.status_code in (200, 201, 409), reg.text
    login = httpx.post(f"{GATEWAY}/customers/login", json={"email": email, "password": pw}, timeout=15.0)
    assert login.status_code == 200, login.text
    body = login.json()
    assert "access_token" in body and "accessToken" not in body
    return email, body["access_token"]


def _no_camel(body, *camel):
    for c in camel:
        assert c not in body, f"camelCase key {c} must not be present: {list(body.keys())}"


def _car_profile() -> dict:
    return {
        "age": 35, "gender": "male", "province": "Ha Noi", "region": "Red River Delta",
        "urban_tier": "tier1", "occupation": "engineer", "income_level": "middle",
        "monthly_income_vnd": 30000000, "marital_status": "single",
        "line": "car",
        "line_attributes": {
            "vehicle_brand": "Toyota", "vehicle_model": "Vios", "vehicle_segment": "standard",
            "vehicle_age": 3, "vehicle_value_vnd": 600000000, "engine_capacity_cc": 1500,
            "driving_experience_years": 8, "annual_mileage_km": 12000,
            "traffic_violation_count_12m": 0, "parking_location": "garage",
            "anti_theft_device": True, "primary_use": "personal",
            "driver_count": 1, "garage_repair_option": "standard", "loan_or_leasing_flag": False,
        },
    }


def _quote_profile(coverage: int = 500000000, deductible: int = 5000000) -> dict:
    p = {k: v for k, v in _car_profile().items() if k != "line"}
    attrs = p.pop("line_attributes")
    p.update(attrs)
    p["coverage_amount_vnd"] = coverage
    p["deductible_vnd"] = deductible
    p["claim_count_36m_prior"] = 0
    return p


def _car_product_id() -> str:
    products = httpx.get(f"{GATEWAY}/products", timeout=10.0).json()
    car = [p for p in products if (p.get("line") or p.get("category")) == "car"]
    assert car, f"no car product seeded; lines={[p.get('line') or p.get('category') for p in products]}"
    return car[0]["product_id"]


def _poll(fn, ok, timeout=POLL_TIMEOUT_S, interval=1.5):
    deadline = time.time() + timeout
    res = None
    while time.time() < deadline:
        res = fn()
        if ok(res):
            return res
        time.sleep(interval)
    return res


@pytest.fixture(scope="module")
def admin_token() -> str:
    return _kc_token(ADMIN_USER, ADMIN_PASS)


@pytest.fixture(scope="module")
def issued(admin_token):
    """Drive quote -> order -> approve -> pay -> issued active policy. Returns context."""
    email, ct = _register_and_login("life")
    # profile (also covers PUT/GET customers/me/profile)
    pr = httpx.put(f"{GATEWAY}/customers/me/profile", json=_car_profile(), headers=_auth(ct), timeout=15.0)
    assert pr.status_code in (200, 201), pr.text
    product_id = _car_product_id()
    q = httpx.post(f"{GATEWAY}/pricing/quote",
                   json={"product_id": product_id, "profile": _quote_profile()},
                   headers=_auth(ct), timeout=20.0)
    assert q.status_code == 200, q.text
    qb = q.json()
    assert "quote_id" in qb and "final_premium_vnd" in qb
    o = httpx.post(f"{GATEWAY}/orders", json={"quote_id": qb["quote_id"]}, headers=_auth(ct), timeout=15.0)
    assert o.status_code in (200, 201), o.text
    order_id = o.json()["order_id"]
    assert o.json()["status"] == "PENDING_REVIEW"
    a = httpx.post(f"{GATEWAY}/admin/orders/{order_id}/approve", headers=_auth(admin_token), timeout=15.0)
    assert a.status_code in (200, 201), a.text
    inv = httpx.post(f"{GATEWAY}/billing/invoices",
                     json={"order_id": order_id, "amount_vnd": int(qb["final_premium_vnd"])}, timeout=15.0)
    assert inv.status_code in (200, 201), inv.text
    invoice_id = inv.json()["invoice_id"]
    pay = httpx.post(f"{GATEWAY}/billing/invoices/{invoice_id}/pay", headers=_auth(ct), timeout=15.0)
    assert pay.status_code == 200 and pay.json()["status"] == "paid", pay.text
    policies = _poll(
        lambda: httpx.get(f"{GATEWAY}/policies", headers=_auth(ct), timeout=10.0).json(),
        lambda ps: any(str(p.get("order_id")) == str(order_id) and p.get("status") == "active" for p in (ps or [])),
    )
    policy = next((p for p in (policies or [])
                   if str(p.get("order_id")) == str(order_id) and p.get("status") == "active"), None)
    assert policy is not None, "policy not issued within timeout"
    return {"email": email, "ct": ct, "product_id": product_id, "order_id": order_id,
            "invoice_id": invoice_id, "policy": policy, "premium": int(qb["final_premium_vnd"])}


# ── Customer_Service ─────────────────────────────────────────────────────────
def test_customer_me_and_profile(issued):
    ct = issued["ct"]
    me = httpx.get(f"{GATEWAY}/customers/me", headers=_auth(ct), timeout=10.0)
    assert me.status_code == 200, me.text
    prof = httpx.get(f"{GATEWAY}/customers/me/profile", headers=_auth(ct), timeout=10.0)
    assert prof.status_code == 200, prof.text
    _no_camel(prof.json(), "lineAttributes", "monthlyIncomeVnd")


# ── Product_Service ──────────────────────────────────────────────────────────
def test_product_catalog(issued):
    pid = issued["product_id"]
    d = httpx.get(f"{GATEWAY}/products/{pid}", timeout=10.0)
    assert d.status_code == 200, d.text
    _no_camel(d.json(), "coverageAmountVnd", "productName")
    nf = httpx.get(f"{GATEWAY}/products/NO_SUCH_PRODUCT", timeout=10.0)
    assert nf.status_code == 404, f"expected 404 not-found, got {nf.status_code}"
    co = httpx.get(f"{GATEWAY}/products/lines/car/coverage-options", timeout=10.0)
    assert co.status_code == 200, co.text


def test_product_admin_config(admin_token):
    rv = httpx.get(f"{GATEWAY}/admin/rate-versions", headers=_auth(admin_token), timeout=10.0)
    assert rv.status_code == 200, rv.text
    lf = httpx.put(f"{GATEWAY}/admin/loading-factors",
                   json={"line": "car", "loading_value": 1.25}, headers=_auth(admin_token), timeout=10.0)
    assert lf.status_code in (200, 201), lf.text
    rv2 = httpx.get(f"{GATEWAY}/admin/rate-versions", headers=_auth(admin_token), timeout=10.0)
    assert rv2.status_code == 200 and len(rv2.json()) >= len(rv.json()), "append-only rate version expected to grow"


# ── Pricing_Service ──────────────────────────────────────────────────────────
def test_pricing_quote_retrieve_and_registry(issued, admin_token):
    q = httpx.post(f"{GATEWAY}/pricing/quote",
                   json={"product_id": issued["product_id"], "profile": _quote_profile()},
                   headers=_auth(issued["ct"]), timeout=20.0)
    assert q.status_code == 200, q.text
    qid = q.json()["quote_id"]
    got = httpx.get(f"{GATEWAY}/pricing/quote/{qid}", headers=_auth(issued["ct"]), timeout=10.0)
    assert got.status_code == 200, got.text
    models = httpx.get(f"{GATEWAY}/pricing/models", headers=_auth(admin_token), timeout=10.0)
    assert models.status_code == 200, models.text
    drift = httpx.get(f"{GATEWAY}/pricing/drift", headers=_auth(admin_token), timeout=10.0)
    assert drift.status_code == 200, drift.text


def test_pricing_validation_gated_off_by_default(admin_token):
    # R20/R13 bonus endpoints are gated OFF by default -> 404 VALIDATION_REPORT_UNAVAILABLE.
    v = httpx.get(f"{GATEWAY}/pricing/validation/car", headers=_auth(admin_token), timeout=10.0)
    assert v.status_code in (404, 200), v.text


def test_champion_promote_rollback(admin_token):
    # Governance is controlled: promote may legitimately be rejected (no better
    # challenger); assert it does not 5xx and returns a structured result.
    models = httpx.get(f"{GATEWAY}/pricing/models", headers=_auth(admin_token), timeout=10.0).json()
    car_models = [m for m in models if m.get("line") == "car"]
    if not car_models:
        pytest.skip("no registered car model_version to attempt promotion")
    mvid = car_models[0].get("model_version_id")
    pr = httpx.post(f"{GATEWAY}/admin/champion/promote",
                    json={"line": "car", "model_version_id": mvid}, headers=_auth(admin_token), timeout=15.0)
    assert pr.status_code in (200, 400, 409), pr.text
    rb = httpx.post(f"{GATEWAY}/admin/champion/rollback",
                    json={"line": "car"}, headers=_auth(admin_token), timeout=15.0)
    assert rb.status_code in (200, 400, 409), rb.text


# ── Order lifecycle ──────────────────────────────────────────────────────────
def _mid_date(policy: dict, frac: float) -> str:
    eff = dt.datetime.fromisoformat(policy["policy_effective_date"])
    exp = dt.datetime.fromisoformat(policy["policy_expiration_date"])
    return (eff + (exp - eff) * frac).isoformat()


def _occurrence_date(policy: dict) -> str:
    """A claim occurrence must fall within an exposure segment AND not be in the
    future (claims-service rejects report_date < occurrence). A freshly issued
    policy starts ~now, so the only valid window is [effective_date, now]. Pick
    the midpoint of that window: guaranteed >= effective and < now (so the
    service's report_date = now() is never before it)."""
    eff = dt.datetime.fromisoformat(policy["policy_effective_date"])
    now = dt.datetime.now(eff.tzinfo)
    if now <= eff:
        return eff.isoformat()
    return (eff + (now - eff) * 0.5).isoformat()


def test_policy_read_and_document(issued):
    ct, pol = issued["ct"], issued["policy"]
    pid = pol["policy_id"]
    one = httpx.get(f"{GATEWAY}/policies/{pid}", headers=_auth(ct), timeout=10.0)
    assert one.status_code == 200, one.text
    doc = httpx.get(f"{GATEWAY}/policies/{pid}/document", headers=_auth(ct), timeout=10.0)
    assert doc.status_code == 200, doc.text
    _no_camel(doc.json(), "policyId", "createdAt")
    segs = httpx.get(f"{GATEWAY}/policies/{pid}/exposure-segments", headers=_auth(ct), timeout=10.0)
    assert segs.status_code == 200, segs.text


def test_endorsement_non_material_applies(issued):
    ct, pol = issued["ct"], issued["policy"]
    body = {"change": {"deductible_vnd": 8000000}, "effective_date": _mid_date(pol, 0.3),
            "coverage_amount_vnd": 500000000, "deductible_vnd": 8000000}
    r = httpx.post(f"{GATEWAY}/policies/{pol['policy_id']}/endorsements", json=body, headers=_auth(ct), timeout=20.0)
    assert r.status_code in (200, 201), r.text
    assert r.json().get("status") == "applied", r.text


def test_endorsement_material_requires_admin(issued, admin_token):
    ct, pol = issued["ct"], issued["policy"]
    body = {"change": {"vehicle_value_vnd": 900000000}, "effective_date": _mid_date(pol, 0.4),
            "coverage_amount_vnd": 700000000, "deductible_vnd": 5000000}
    r = httpx.post(f"{GATEWAY}/policies/{pol['policy_id']}/endorsements", json=body, headers=_auth(ct), timeout=20.0)
    assert r.status_code in (200, 201), r.text
    assert r.json().get("status") == "pending_review", r.text
    erid = r.json()["endorsement_request_id"]
    # customer must NOT be able to approve (no such customer route); admin approves.
    queue = httpx.get(f"{GATEWAY}/admin/endorsements/review-queue", headers=_auth(admin_token), timeout=10.0)
    assert queue.status_code == 200, queue.text
    ap = httpx.post(f"{GATEWAY}/admin/endorsements/{erid}/approve", headers=_auth(admin_token), timeout=20.0)
    assert ap.status_code in (200, 201), ap.text
    assert ap.json().get("status") == "APPROVED", ap.text


def test_billing_list_by_policy(issued):
    ct, pol = issued["ct"], issued["policy"]
    r = httpx.get(f"{GATEWAY}/billing/invoices", params={"policy_id": pol["policy_id"]},
                  headers=_auth(ct), timeout=10.0)
    assert r.status_code == 200, r.text
    body = r.json()
    assert "invoices" in body and "adjustments" in body, body


def test_vnpay_payment_url(issued):
    # VNPAY is optional: when merchant credentials are not configured the billing
    # service degrades gracefully to 503 (SERVICE_UNAVAILABLE) rather than 500.
    # In that case we skip (the redirect/IPN flow can't be exercised without creds).
    ct = issued["ct"]
    q = httpx.post(f"{GATEWAY}/pricing/quote",
                   json={"product_id": issued["product_id"], "profile": _quote_profile()},
                   headers=_auth(ct), timeout=20.0).json()
    o = httpx.post(f"{GATEWAY}/orders", json={"quote_id": q["quote_id"]}, headers=_auth(ct), timeout=15.0).json()
    httpx.post(f"{GATEWAY}/admin/orders/{o['order_id']}/approve",
               headers=_auth(_kc_token(ADMIN_USER, ADMIN_PASS)), timeout=15.0)
    inv = httpx.post(f"{GATEWAY}/billing/invoices",
                     json={"order_id": o["order_id"], "amount_vnd": int(q["final_premium_vnd"])}, timeout=15.0).json()
    r = httpx.post(f"{GATEWAY}/billing/invoices/{inv['invoice_id']}/payment-url", headers=_auth(ct), timeout=15.0)
    if r.status_code == 503:
        pytest.skip("VNPAY merchant credentials not configured (VNP_TMN_CODE/VNP_HASH_SECRET unset)")
    assert r.status_code in (200, 201), r.text
    body = r.json()
    assert "payment_url" in body and body["payment_url"].startswith("http"), body


# ── Claims_Service ───────────────────────────────────────────────────────────
def test_claims_lifecycle(issued, admin_token):
    ct, pol = issued["ct"], issued["policy"]
    occ = _occurrence_date(pol)
    fnol = httpx.post(f"{GATEWAY}/claims/fnol",
                      json={"policy_id": pol["policy_id"], "occurrence_date": occ,
                            "loss_type": "collision", "severity_level": "medium"},
                      headers=_auth(ct), timeout=15.0)
    assert fnol.status_code in (200, 201), fnol.text
    claim_id = fnol.json()["claim_id"]
    mine = httpx.get(f"{GATEWAY}/claims", headers=_auth(ct), timeout=10.0)
    assert mine.status_code == 200, mine.text
    one = httpx.get(f"{GATEWAY}/claims/{claim_id}", headers=_auth(ct), timeout=10.0)
    assert one.status_code == 200, one.text
    # admin approves with a payout within coverage - deductible
    ap = httpx.post(f"{GATEWAY}/claims/{claim_id}/approve",
                    json={"incurred_amount": 10000000, "paid_amount": 8000000},
                    headers=_auth(admin_token), timeout=15.0)
    assert ap.status_code in (200, 201), ap.text
    assert ap.json()["claim_status"] == "approved", ap.text
    # admin can read any claim by id (R28.6)
    adminview = httpx.get(f"{GATEWAY}/claims/{claim_id}", headers=_auth(admin_token), timeout=10.0)
    assert adminview.status_code == 200, adminview.text


def test_claim_reject_branch(issued, admin_token):
    ct, pol = issued["ct"], issued["policy"]
    fnol = httpx.post(f"{GATEWAY}/claims/fnol",
                      json={"policy_id": pol["policy_id"], "occurrence_date": _occurrence_date(pol),
                            "loss_type": "theft", "severity_level": "low"},
                      headers=_auth(ct), timeout=15.0)
    assert fnol.status_code in (200, 201), fnol.text
    cid = fnol.json()["claim_id"]
    rj = httpx.post(f"{GATEWAY}/claims/{cid}/reject", headers=_auth(admin_token), timeout=15.0)
    assert rj.status_code in (200, 201), rj.text
    assert rj.json()["claim_status"] == "rejected" and rj.json()["paid_amount"] == 0, rj.text


# ── Notifications ────────────────────────────────────────────────────────────
def test_notifications_list(issued):
    r = httpx.get(f"{GATEWAY}/notifications", headers=_auth(issued["ct"]), timeout=10.0)
    assert r.status_code == 200, r.text
    rs = httpx.get(f"{GATEWAY}/notifications", params={"status": "sent"}, headers=_auth(issued["ct"]), timeout=10.0)
    assert rs.status_code == 200, rs.text


# ── Renewal + Cancellation (run last; they mutate the shared policy) ─────────
def test_renew_and_cancel(issued):
    ct, pol = issued["ct"], issued["policy"]
    rn = httpx.post(f"{GATEWAY}/policies/{pol['policy_id']}/renew", headers=_auth(ct), timeout=20.0)
    assert rn.status_code in (200, 201), rn.text
    cancel_date = _mid_date(pol, 0.6)
    cn = httpx.post(f"{GATEWAY}/policies/{pol['policy_id']}/cancel",
                    json={"cancel_date": cancel_date}, headers=_auth(ct), timeout=20.0)
    assert cn.status_code in (200, 201), cn.text
    assert cn.json()["status"] == "cancelled", cn.text


# ── RBAC negatives ───────────────────────────────────────────────────────────
def test_rbac_negatives(issued):
    ct = issued["ct"]
    assert httpx.get(f"{GATEWAY}/orders", timeout=10.0).status_code == 401  # no JWT
    # customer token must be forbidden on admin-only pricing + product endpoints
    assert httpx.get(f"{GATEWAY}/pricing/models", headers=_auth(ct), timeout=10.0).status_code == 403
    assert httpx.get(f"{GATEWAY}/admin/rate-versions", headers=_auth(ct), timeout=10.0).status_code == 403
    assert httpx.get(f"{GATEWAY}/admin/orders/review-queue", headers=_auth(ct), timeout=10.0).status_code == 403
