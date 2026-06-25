"""Real end-to-end + cross-service ownership tests through Kong.

Feature: dynamic-pricing-platform
Validates: R6.4, R6.10, R6.11, R18.3, R33.2, R34.1, R7.1, R15.1, R15.2.

These run against the live stack via Kong (http://localhost:8000) brought up by
`docker compose up`. They SKIP (not pass) when the gateway or Keycloak is not
reachable, so CI without the stack does not get false confidence.

Task 20.16: implements the full happy-path chain through Kong instead of stopping
at PENDING_PAYMENT:

    register/login demo.customer -> PUT profile -> POST /pricing/quote
    -> POST /orders (PENDING_REVIEW) -> admin POST /admin/orders/{id}/approve
    (PENDING_PAYMENT + invoice created) -> POST /billing/invoices/{id}/pay
    -> poll GET /policies until the issued policy is `active`
    -> poll GET /notifications until the PolicyIssued notification is persisted.

That asserts the cross-service saga: invoice-paid -> PolicyIssued -> policy active
-> notification persisted. A separate REJECT-branch test asserts a rejected order
issues no policy. Cross-service ownership is asserted with a second registered
customer who must not read the first customer's policy (403).

Task 25.11: verifies snake_case JSON contracts end-to-end through Kong -- both
request payloads (snake_case keys) and response bodies (snake_case keys, not
camelCase), confirming the global Jackson SNAKE_CASE strategy (task 25.1) is
applied at every service boundary.
"""
from __future__ import annotations

import os
import time
import uuid

import pytest

httpx = pytest.importorskip("httpx")

GATEWAY = os.getenv("DPP_GATEWAY_URL", "http://localhost:8000")
KEYCLOAK = os.getenv("DPP_KEYCLOAK_URL", "http://localhost:8080")
REALM = os.getenv("KEYCLOAK_REALM", "dynamic-pricing")

CUSTOMER_USER = os.getenv("DPP_DEMO_CUSTOMER", "demo.customer")
CUSTOMER_PASS = os.getenv("DPP_DEMO_CUSTOMER_PASS", "demo_customer_dev_only")
ADMIN_USER = os.getenv("DPP_DEMO_ADMIN", "demo.admin")
ADMIN_PASS = os.getenv("DPP_DEMO_ADMIN_PASS", "demo_admin_dev_only")

# How long to wait for the asynchronous saga (InvoicePaid -> PolicyIssued ->
# notification) to settle. The chain crosses order/billing/notification services
# over RabbitMQ, so it is eventually-consistent.
POLL_TIMEOUT_S = float(os.getenv("DPP_E2E_POLL_TIMEOUT", "45"))
POLL_INTERVAL_S = 1.5


def _stack_up() -> bool:
    try:
        r = httpx.get(f"{GATEWAY}/products", timeout=3.0)
        return r.status_code < 500
    except Exception:
        return False


# Module-level guard: ping Kong /products once at import; SKIP (not pass) the whole
# module when the gateway is unreachable so CI without a stack stays green.
pytestmark = pytest.mark.skipif(not _stack_up(), reason="Live stack (Kong) not reachable on :8000")


def _token(username: str, password: str) -> str:
    r = httpx.post(
        f"{KEYCLOAK}/realms/{REALM}/protocol/openid-connect/token",
        data={
            "grant_type": "password",
            "client_id": "mini-app",
            "username": username,
            "password": password,
            "scope": "openid",
        },
        timeout=10.0,
    )
    r.raise_for_status()
    return r.json()["access_token"]


def _auth(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def _profile_payload() -> dict:
    """snake_case profile payload (task 25.1 SNAKE_CASE request contract)."""
    return {
        "age": 30,
        "gender": "male",
        "province": "Ha Noi",
        "region": "Red River Delta",
        "urban_tier": "tier1",
        "occupation": "engineer",
        "income_level": "middle",
        "monthly_income_vnd": 25000000,
        "marital_status": "single",
        "line": "health",
        "line_attributes": {
            "height_cm": 170, "weight_kg": 65, "bmi": 22.5,
            "smoker": False, "chronic_disease": False, "diabetes": False,
            "blood_pressure_problem": False, "major_surgeries_count": 0,
            "hospitalized_last_12m": False, "medical_visit_count_12m": 1,
        },
    }


def _quote_payload(product_id: str) -> dict:
    p = _profile_payload()
    return {
        "product_id": product_id,
        "profile": {
            **{k: v for k, v in p.items() if k != "line"},
            "coverage_amount_vnd": 100000000,
            "deductible_vnd": 0,
        },
    }


def _first_product_id() -> str:
    products = httpx.get(f"{GATEWAY}/products", timeout=10.0).json()
    assert products, "no products seeded"
    pid = products[0].get("product_id") or products[0].get("productId")
    assert pid, f"product missing id, keys: {list(products[0].keys())}"
    return pid


def _poll(fn, predicate, timeout: float = POLL_TIMEOUT_S, interval: float = POLL_INTERVAL_S):
    """Poll fn() until predicate(result) is truthy or timeout. Returns the last
    result (which may not satisfy the predicate) so callers can assert on it."""
    deadline = time.time() + timeout
    result = None
    while time.time() < deadline:
        result = fn()
        if predicate(result):
            return result
        time.sleep(interval)
    return result


def _create_paid_order(customer_token: str, admin_token: str) -> dict:
    """Drive quote -> order -> approve -> retrieve invoice -> pay through Kong.

    Returns the collected snake_case response bodies. Invoice id is recovered via
    the idempotent POST /billing/invoices (findByOrderId, task 20.11): order
    approval already created the invoice, so re-posting returns the same one
    instead of creating a duplicate -- this is the only read path to the invoice
    id before a policy exists (policy_id is null until payment)."""
    product_id = _first_product_id()

    quote = httpx.post(f"{GATEWAY}/pricing/quote", json=_quote_payload(product_id),
                       headers=_auth(customer_token), timeout=20.0)
    assert quote.status_code == 200, quote.text
    quote_body = quote.json()
    assert "quote_id" in quote_body, f"snake_case quote_id expected, keys: {list(quote_body.keys())}"
    assert "quoteId" not in quote_body, "camelCase quoteId must not be present"
    assert "final_premium_vnd" in quote_body, f"snake_case final_premium_vnd expected, keys: {list(quote_body.keys())}"
    quote_id = quote_body["quote_id"]
    premium = int(quote_body["final_premium_vnd"])
    assert premium > 0

    order = httpx.post(f"{GATEWAY}/orders", json={"quote_id": quote_id},
                       headers=_auth(customer_token), timeout=15.0)
    assert order.status_code in (200, 201), order.text
    order_body = order.json()
    assert "order_id" in order_body, f"snake_case order_id expected, keys: {list(order_body.keys())}"
    assert "orderId" not in order_body, "camelCase orderId must not be present"
    assert "final_premium_vnd" in order_body, "order response must use snake_case final_premium_vnd"
    assert order_body["status"] == "PENDING_REVIEW"
    order_id = order_body["order_id"]

    appr = httpx.post(f"{GATEWAY}/admin/orders/{order_id}/approve",
                      headers=_auth(admin_token), timeout=15.0)
    assert appr.status_code in (200, 201), appr.text
    appr_body = appr.json()
    assert appr_body["status"] == "PENDING_PAYMENT"

    # Recover the invoice id (idempotent create returns the one made at approval).
    inv = httpx.post(f"{GATEWAY}/billing/invoices",
                     json={"order_id": order_id, "amount_vnd": premium}, timeout=15.0)
    assert inv.status_code in (200, 201), inv.text
    inv_body = inv.json()
    assert "invoice_id" in inv_body, f"snake_case invoice_id expected, keys: {list(inv_body.keys())}"
    assert "invoiceId" not in inv_body, "camelCase invoiceId must not be present"
    assert str(inv_body["order_id"]) == str(order_id)
    invoice_id = inv_body["invoice_id"]

    pay = httpx.post(f"{GATEWAY}/billing/invoices/{invoice_id}/pay",
                     headers=_auth(customer_token), timeout=15.0)
    assert pay.status_code == 200, pay.text
    pay_body = pay.json()
    assert pay_body["status"] == "paid", f"invoice must be paid, got {pay_body.get('status')}"
    assert "paidAt" not in pay_body, "camelCase paidAt must not be present"

    return {
        "order_id": order_id,
        "invoice_id": invoice_id,
        "premium": premium,
        "quote": quote_body,
        "order": order_body,
        "approve": appr_body,
        "invoice": inv_body,
        "pay": pay_body,
    }


@pytest.fixture(scope="module")
def customer_token() -> str:
    return _token(CUSTOMER_USER, CUSTOMER_PASS)


@pytest.fixture(scope="module")
def admin_token() -> str:
    return _token(ADMIN_USER, ADMIN_PASS)


@pytest.fixture(scope="module")
def issued_policy(customer_token, admin_token) -> dict:
    """Run the happy-path chain once and resolve the issued policy + notification.

    Reused by the happy-path assertions and the cross-service ownership test so
    the ownership check runs against a *real* policy owned by demo.customer."""
    chain = _create_paid_order(customer_token, admin_token)
    order_id = chain["order_id"]

    # invoice-paid -> PolicyIssued -> policy active (eventually consistent over RabbitMQ).
    def list_policies():
        r = httpx.get(f"{GATEWAY}/policies", headers=_auth(customer_token), timeout=10.0)
        assert r.status_code == 200, r.text
        return r.json()

    def has_active_policy(policies):
        return any(
            str(p.get("order_id")) == str(order_id) and p.get("status") == "active"
            for p in (policies or [])
        )

    policies = _poll(list_policies, has_active_policy)
    policy = next((p for p in (policies or [])
                   if str(p.get("order_id")) == str(order_id) and p.get("status") == "active"), None)
    assert policy is not None, f"no active policy issued for order {order_id} within {POLL_TIMEOUT_S}s"
    chain["policy"] = policy

    # notification persisted for the issued policy (PolicyIssued).
    policy_id = policy["policy_id"]

    def list_notifications():
        r = httpx.get(f"{GATEWAY}/notifications", headers=_auth(customer_token), timeout=10.0)
        assert r.status_code == 200, r.text
        return r.json()

    def has_policy_notification(notifs):
        return any(str(n.get("policy_id")) == str(policy_id) for n in (notifs or []))

    notifs = _poll(list_notifications, has_policy_notification)
    policy_notifs = [n for n in (notifs or []) if str(n.get("policy_id")) == str(policy_id)]
    assert policy_notifs, f"no notification persisted for policy {policy_id} within {POLL_TIMEOUT_S}s"
    chain["notifications"] = policy_notifs
    return chain


# ─────────────────────────── gateway/contract sanity ───────────────────────────

def test_public_products_reachable_without_token():
    r = httpx.get(f"{GATEWAY}/products", timeout=10.0)
    assert r.status_code == 200


def test_protected_route_requires_token():
    r = httpx.get(f"{GATEWAY}/orders", timeout=10.0)
    assert r.status_code == 401


def test_products_response_uses_snake_case_keys():
    """R15.1/R15.2: product responses must use snake_case (task 25.1)."""
    r = httpx.get(f"{GATEWAY}/products", timeout=10.0)
    assert r.status_code == 200
    products = r.json()
    assert products, "no products seeded"
    first = products[0]
    assert "product_id" in first, f"expected snake_case product_id, keys: {list(first.keys())}"
    assert "productId" not in first, "camelCase productId must not be present"


def test_profile_update_uses_snake_case():
    """R15.1/R15.2: PUT /customers/me/profile accepts and returns snake_case.

    Uses a freshly registered customer (which creates the backing Account row);
    demo.customer is a Keycloak-only convenience user without a customer Account,
    so profile writes for it are not applicable.
    """
    email = f"e2e.profile.{uuid.uuid4().hex[:12]}@example.com"
    token = _register_and_login(email, "profile_dev_only_pw")
    r = httpx.put(f"{GATEWAY}/customers/me/profile", json=_profile_payload(),
                  headers=_auth(token), timeout=15.0)
    assert r.status_code in (200, 201), r.text
    body = r.json()
    assert "customer_id" in body, f"expected snake_case customer_id, keys: {list(body.keys())}"
    assert "customerId" not in body, "camelCase customerId must not be present"
    assert "urbanTier" not in body and "monthlyIncomeVnd" not in body, "camelCase keys must not be present"


# ─────────────────────────── full happy-path chain ───────────────────────────

def test_end_to_end_quote_to_policy_to_notification(issued_policy):
    """invoice-paid -> PolicyIssued -> policy active -> notification persisted."""
    chain = issued_policy

    # order approved then paid
    assert chain["approve"]["status"] == "PENDING_PAYMENT"
    assert chain["pay"]["status"] == "paid"

    # policy issued and active, linked to our order (snake_case contract)
    policy = chain["policy"]
    assert policy["status"] == "active"
    assert "policy_id" in policy and "policyId" not in policy
    assert "final_premium_vnd" in policy, f"policy must use snake_case, keys: {list(policy.keys())}"
    assert str(policy["order_id"]) == str(chain["order_id"])

    # notification persisted for the issued policy (snake_case contract)
    notif = chain["notifications"][0]
    assert "notification_id" in notif and "notificationId" not in notif
    assert str(notif.get("policy_id")) == str(policy["policy_id"])
    assert notif.get("type"), "notification must carry a type (e.g. PolicyIssued)"


def test_reject_branch_issues_no_policy(customer_token, admin_token):
    """REJECT branch: a rejected order ends REJECTED and issues no policy.

    Invoices are only created on approve() (OrderService), so a rejected order
    never produces one; we assert no policy is ever issued for it. We deliberately
    do NOT probe POST /billing/invoices here -- that endpoint is idempotent-create
    and would *create* an invoice, defeating the assertion."""
    product_id = _first_product_id()
    quote = httpx.post(f"{GATEWAY}/pricing/quote", json=_quote_payload(product_id),
                       headers=_auth(customer_token), timeout=20.0)
    assert quote.status_code == 200, quote.text
    quote_id = quote.json()["quote_id"]

    order = httpx.post(f"{GATEWAY}/orders", json={"quote_id": quote_id},
                       headers=_auth(customer_token), timeout=15.0)
    assert order.status_code in (200, 201), order.text
    order_id = order.json()["order_id"]

    rej = httpx.post(f"{GATEWAY}/admin/orders/{order_id}/reject",
                     json={"reason": "e2e reject branch"},
                     headers=_auth(admin_token), timeout=15.0)
    assert rej.status_code in (200, 201), rej.text
    rej_body = rej.json()
    assert rej_body["status"] == "REJECTED"
    assert rej_body.get("review_decision") in ("REJECT", None)
    assert "reviewDecision" not in rej_body, "camelCase reviewDecision must not be present"

    # admin view confirms the persisted REJECTED state
    got = httpx.get(f"{GATEWAY}/admin/orders/{order_id}", headers=_auth(admin_token), timeout=10.0)
    assert got.status_code == 200, got.text
    assert got.json()["status"] == "REJECTED"

    # no policy should ever be issued for a rejected order (give the saga a moment)
    def list_policies():
        r = httpx.get(f"{GATEWAY}/policies", headers=_auth(customer_token), timeout=10.0)
        assert r.status_code == 200, r.text
        return r.json()

    policies = _poll(list_policies,
                     lambda ps: any(str(p.get("order_id")) == str(order_id) for p in (ps or [])),
                     timeout=8.0)
    assert not any(str(p.get("order_id")) == str(order_id) for p in (policies or [])), \
        f"rejected order {order_id} must not have an issued policy"


# ─────────────────────────── cross-service ownership ───────────────────────────

def _register_and_login(email: str, password: str) -> str:
    reg = httpx.post(f"{GATEWAY}/customers/register",
                     json={"email": email, "password": password}, timeout=15.0)
    # 201 created, or already-exists on a rerun -> proceed to login regardless.
    assert reg.status_code in (200, 201, 409), reg.text
    login = httpx.post(f"{GATEWAY}/customers/login",
                       json={"email": email, "password": password}, timeout=15.0)
    assert login.status_code == 200, login.text
    body = login.json()
    token = body.get("access_token")
    assert token, f"login must return snake_case access_token, keys: {list(body.keys())}"
    assert "accessToken" not in body, "camelCase accessToken must not be present"
    return token


def test_cross_service_ownership_rejected(issued_policy):
    """R18.3/R34.1: customer B must not read customer A's policy (403)."""
    policy_id = issued_policy["policy"]["policy_id"]

    # Customer B is a freshly registered account that owns nothing.
    email = f"e2e.owner.{uuid.uuid4().hex[:12]}@example.com"
    customer_b = _register_and_login(email, "owner_dev_only_pw")

    # B reading A's real policy -> 403 FORBIDDEN_RESOURCE (policy exists, not owned).
    r = httpx.get(f"{GATEWAY}/policies/{policy_id}", headers=_auth(customer_b), timeout=10.0)
    assert r.status_code == 403, f"expected 403 for non-owner policy read, got {r.status_code}: {r.text}"

    # B reading an arbitrary claim it does not own -> 403/404 (isolation holds).
    foreign_claim = str(uuid.uuid4())
    rc = httpx.get(f"{GATEWAY}/claims/{foreign_claim}", headers=_auth(customer_b), timeout=10.0)
    assert rc.status_code in (403, 404), rc.text


def test_unowned_policy_uuid_rejected(customer_token):
    """A random (non-owned) policy id is never readable -> 403/404."""
    foreign_policy = str(uuid.uuid4())
    r = httpx.get(f"{GATEWAY}/policies/{foreign_policy}", headers=_auth(customer_token), timeout=10.0)
    assert r.status_code in (403, 404), r.text


def test_error_response_uses_snake_case_keys():
    """R15.2/R19.3: structured error responses must use snake_case keys."""
    r = httpx.get(f"{GATEWAY}/orders", timeout=10.0)
    assert r.status_code == 401
    # Some gateways return plain 401 without body; if body exists, check snake_case
    if r.headers.get("content-type", "").startswith("application/json"):
        body = r.json()
        if "error_code" in body or "code" in body:
            assert "errorCode" not in body, "camelCase errorCode must not be present"
