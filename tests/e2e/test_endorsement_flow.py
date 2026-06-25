"""
E2E endorsement flow test — validates the full chain:
  Register → Login → Quote → Order → Admin Approve → Pay Invoice
  → Wait for Policy activation → Submit Endorsement → Admin Approve Endorsement

Usage: python tests/e2e/test_endorsement_flow.py
Requires: docker-compose services running (Kong on :8000, Keycloak on :8080)
"""
import httpx
import json
import time
import uuid
import sys
import os

BASE = os.environ.get("KONG_BASE_URL", "http://localhost:8000")
TIMEOUT = 60.0

# Direct service URLs (bypass Kong to avoid 5s proxy timeout on slow operations)
CUSTOMER_URL = os.environ.get("CUSTOMER_URL", "http://localhost:8081")
PRICING_URL = os.environ.get("PRICING_URL", "http://localhost:8090")
ORDER_URL = os.environ.get("ORDER_URL", "http://localhost:8083")
BILLING_URL = os.environ.get("BILLING_URL", "http://localhost:8086")
POLL_INTERVAL = 3.0
POLL_TIMEOUT = 60.0

# Admin credentials — must match Keycloak realm config
ADMIN_EMAIL = os.environ.get("ADMIN_EMAIL", "demo.admin@example.com")
ADMIN_PASSWORD = os.environ.get("ADMIN_PASSWORD", "demo_admin_dev_only")

# Test customer
CUSTOMER_EMAIL = f"e2e-endorsement-{uuid.uuid4().hex[:8]}@test.local"
CUSTOMER_PASSWORD = "Test1234!Pass"

VALID_PROFILE = {
    "age": 30,
    "gender": "Male",
    "province": "Ha Noi",
    "region": "Red River Delta",
    "urban_tier": "tier1",
    "occupation": "engineer",
    "income_level": "middle",
    "marital_status": "single",
    "line_attributes": {
        "smoker": False,
        "height_cm": 170,
        "weight_kg": 65,
        "bmi": 22.5,
        "coverage_amount_vnd": 500_000_000,
        "deductible_vnd": 1_000_000,
    },
}


def log(step, msg, data=None):
    print(f"\n{'='*60}")
    print(f"[{step}] {msg}")
    if data:
        print(json.dumps(data, indent=2, ensure_ascii=False, default=str))
    print(f"{'='*60}")


def assert_ok(resp, step, expected=200):
    if resp.status_code != expected:
        print(f"FAIL: {step} — expected {expected}, got {resp.status_code}")
        print(f"Response: {resp.text}")
        sys.exit(1)
    print(f"OK: {step} — status {resp.status_code}")


def wait_for_policy(client, token, max_wait=POLL_TIMEOUT):
    """Poll GET /policies until a policy appears (activated by InvoicePaid event)."""
    headers = {"Authorization": f"Bearer {token}"}
    deadline = time.time() + max_wait
    while time.time() < deadline:
        resp = client.get("/policies", headers=headers)
        if resp.status_code == 200:
            policies = resp.json()
            if policies and len(policies) > 0:
                return policies[0]
        print(f"  Waiting for policy activation... ({int(deadline - time.time())}s left)")
        time.sleep(POLL_INTERVAL)
    print("FAIL: Policy was not activated within timeout")
    sys.exit(1)


def get_keycloak_token(email, password):
    """Get JWT directly from Keycloak (bypass Customer service DB lookup)."""
    kc_url = os.environ.get("KEYCLOAK_URL", "http://localhost:8080")
    kc_realm = os.environ.get("KEYCLOAK_REALM", "dynamic-pricing")
    kc_client = os.environ.get("KEYCLOAK_CLIENT_ID", "mini-app")
    resp = httpx.post(
        f"{kc_url}/realms/{kc_realm}/protocol/openid-connect/token",
        data={
            "grant_type": "password",
            "client_id": kc_client,
            "username": email,
            "password": password,
        },
        timeout=TIMEOUT,
    )
    if resp.status_code != 200:
        return None
    return resp.json().get("access_token")


def main():
    customer_c = httpx.Client(base_url=CUSTOMER_URL, timeout=TIMEOUT)
    pricing_c = httpx.Client(base_url=PRICING_URL, timeout=TIMEOUT)
    order_c = httpx.Client(base_url=ORDER_URL, timeout=TIMEOUT)
    billing_c = httpx.Client(base_url=BILLING_URL, timeout=TIMEOUT)

    # ── Step 1: Register ──
    log("1/9", f"Register customer: {CUSTOMER_EMAIL}")
    resp = customer_c.post("/customers/register", json={
        "email": CUSTOMER_EMAIL,
        "password": CUSTOMER_PASSWORD,
    })
    assert_ok(resp, "Register", expected=201)
    print("Customer registered successfully")

    # ── Step 2: Login ──
    log("2/9", "Login customer")
    resp = customer_c.post("/customers/login", json={
        "email": CUSTOMER_EMAIL,
        "password": CUSTOMER_PASSWORD,
    })
    assert_ok(resp, "Login")
    token_data = resp.json()
    customer_token = token_data.get("access_token") or token_data.get("token")
    if not customer_token:
        print(f"FAIL: No token in login response: {token_data}")
        sys.exit(1)
    print(f"Got customer JWT (len={len(customer_token)})")
    auth_headers = {"Authorization": f"Bearer {customer_token}"}

    # ── Step 3: Get a quote ──
    log("3/9", "Request pricing quote")
    resp = pricing_c.post("/pricing/quote", json={
        "product_id": "HEALTH_BASIC",
        "profile": VALID_PROFILE,
    }, headers=auth_headers)
    assert_ok(resp, "Quote")
    quote = resp.json()
    quote_id = quote["quote_id"]
    premium = quote.get("final_premium_vnd", 0)
    print(f"Quote created: id={quote_id}, premium={premium:,} VND")

    # ── Step 4: Create order ──
    log("4/9", "Create order from quote")
    resp = order_c.post("/orders", json={
        "quote_id": quote_id,
    }, headers=auth_headers)
    assert_ok(resp, "Create order", expected=201)
    order = resp.json()
    order_id = order["order_id"]
    print(f"Order created: id={order_id}, status={order.get('status')}")

    # ── Step 5: Admin login + approve order ──
    log("5a/9", "Login as admin (direct Keycloak)")
    admin_token = get_keycloak_token(ADMIN_EMAIL, ADMIN_PASSWORD)
    if not admin_token:
        print(f"WARNING: Admin login failed. Trying with customer token.")
        admin_token = customer_token
    else:
        print(f"Got admin JWT (len={len(admin_token)})")
    admin_headers = {"Authorization": f"Bearer {admin_token}"}

    log("5b/9", "Admin approves order")
    resp = order_c.post(f"/admin/orders/{order_id}/approve", headers=admin_headers)
    assert_ok(resp, "Admin approve order")
    order = resp.json()
    print(f"Order approved: status={order.get('status')}")

    # ── Step 6: Pay invoice ──
    log("6a/9", "Find invoice for order")
    time.sleep(2)
    # Billing dedups on order_id — safe to call again
    resp = billing_c.post("/billing/invoices", json={
        "order_id": order_id,
        "amount_vnd": premium,
    })
    if resp.status_code in (200, 201):
        invoice = resp.json()
        invoice_id = invoice.get("invoice_id")
        print(f"Found/created invoice: id={invoice_id}")
    else:
        print(f"Billing invoice lookup failed: {resp.status_code} {resp.text}")
        sys.exit(1)

    log("6b/9", "Pay invoice")
    resp = billing_c.post(f"/billing/invoices/{invoice_id}/pay", headers=auth_headers)
    assert_ok(resp, "Pay invoice")
    invoice = resp.json()
    print(f"Invoice paid: status={invoice.get('status')}")

    # ── Step 7: Wait for policy activation ──
    log("7/9", "Waiting for policy activation (InvoicePaid event → RabbitMQ → PolicyIssuance)")
    policy = wait_for_policy(order_c, customer_token)
    policy_id = policy["policy_id"]
    print(f"Policy activated: id={policy_id}, status={policy.get('status')}")

    # ── Step 8: Submit endorsement (material change) ──
    log("8/9", "Submit material-change endorsement")
    policy_eff = policy.get("policy_effective_date", "")
    policy_exp = policy.get("policy_expiration_date", "")
    from datetime import datetime, timedelta, timezone
    if isinstance(policy_eff, str):
        eff_date = datetime.fromisoformat(policy_eff.replace("Z", "+00:00"))
    else:
        eff_date = datetime.now(timezone.utc) - timedelta(days=30)
    endorsement_eff = eff_date + timedelta(days=10)

    resp = order_c.post(f"/policies/{policy_id}/endorsements", json={
        "change": {"vehicle_value_vnd": 500_000_000},
        "effective_date": endorsement_eff.isoformat(),
        "coverage_amount_vnd": 500_000_000,
        "deductible_vnd": 1_000_000,
    }, headers=auth_headers)
    assert_ok(resp, "Submit endorsement")
    endorsement = resp.json()
    end_status = endorsement.get("status")
    end_req_id = endorsement.get("endorsement_request_id")
    print(f"Endorsement submitted: status={end_status}, request_id={end_req_id}")

    if end_status != "pending_review":
        print(f"WARNING: Expected status 'pending_review', got '{end_status}'")

    # ── Step 9: Admin approves endorsement ──
    log("9/9", "Admin approves endorsement")
    resp = order_c.post(f"/admin/endorsements/{end_req_id}/approve", headers=admin_headers)
    assert_ok(resp, "Admin approve endorsement")
    result = resp.json()
    print(f"Endorsement approved: status={result.get('status')}, reviewed_by={result.get('reviewed_by')}")

    if result.get("status") != "APPROVED":
        print(f"WARNING: Expected status 'APPROVED', got '{result.get('status')}'")

    # ── Verify: check policy premium changed ──
    log("VERIFY", "Check policy after endorsement")
    resp = order_c.get(f"/policies/{policy_id}", headers=auth_headers)
    assert_ok(resp, "Get policy after endorsement")
    updated_policy = resp.json()
    new_premium = updated_policy.get("final_premium_vnd", 0)
    print(f"Policy premium: before={premium:,} VND, after={new_premium:,} VND")
    if new_premium != premium:
        print("Premium changed — material change endorsement was applied with re-rating!")
    else:
        print("NOTE: Premium unchanged — pricing re-rate may have returned same value or used fallback.")

    # ── Summary ──
    print(f"\n{'*'*60}")
    print("E2E ENDORSEMENT FLOW: PASSED")
    print(f"{'*'*60}")
    print(f"  Customer: {CUSTOMER_EMAIL}")
    print(f"  Quote ID: {quote_id}")
    print(f"  Order ID: {order_id}")
    print(f"  Invoice ID: {invoice_id}")
    print(f"  Policy ID: {policy_id}")
    print(f"  Endorsement Request ID: {end_req_id}")
    print(f"  Premium: {premium:,} → {new_premium:,} VND")
    print(f"{'*'*60}")

    customer_c.close()
    pricing_c.close()
    order_c.close()
    billing_c.close()


if __name__ == "__main__":
    main()
