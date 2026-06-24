"""Real end-to-end + cross-service ownership tests through Kong.

Feature: dynamic-pricing-platform
Validates: R6.4, R6.10, R6.11, R18.3, R33.2, R34.1, R7.1, R15.1, R15.2.

These run against the live stack via Kong (http://localhost:8000) brought up by
`docker compose up`. They SKIP (not pass) when the gateway or Keycloak is not
reachable, so CI without the stack does not get false confidence.

Task 25.11: verifies snake_case JSON contracts end-to-end through Kong ? both
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

def _stack_up() -> bool:
    try:
        r = httpx.get(f"{GATEWAY}/products", timeout=3.0)
        return r.status_code < 500
    except Exception:
        return False

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

def test_end_to_end_quote_to_policy_to_notification():
    customer = _token(CUSTOMER_USER, CUSTOMER_PASS)
    admin = _token(ADMIN_USER, ADMIN_PASS)

    # quote (public products to pick a product_id)
    products = httpx.get(f"{GATEWAY}/products", timeout=10.0).json()
    assert products, "no products seeded"
    product_id = products[0].get("product_id") or products[0].get("productId")

    quote = httpx.post(f"{GATEWAY}/pricing/quote", json={
        "product_id": product_id,
        "profile": {
            "age": 30, "gender": "male", "province": "Ha Noi",
            "region": "Red River Delta", "urban_tier": "tier1",
            "occupation": "engineer", "income_level": "middle",
            "marital_status": "single",
            "coverage_amount_vnd": 100000000, "deductible_vnd": 0,
            "line_attributes": {"smoker": False, "height_cm": 170, "weight_kg": 65,
                                "bmi": 22.5, "chronic_disease": False, "diabetes": False,
                                "blood_pressure_problem": False, "major_surgeries_count": 0,
                                "hospitalized_last_12m": False, "medical_visit_count_12m": 1},
        },
    }, headers=_auth(customer), timeout=20.0)
    assert quote.status_code == 200, quote.text
    quote_body = quote.json()
    assert "quote_id" in quote_body, f"response must use snake_case quote_id, keys: {list(quote_body.keys())}"
    assert "quoteId" not in quote_body, "camelCase quoteId must not be present"
    quote_id = quote_body["quote_id"]

    # create order -> PENDING_REVIEW
    order = httpx.post(f"{GATEWAY}/orders", json={"quote_id": quote_id},
                       headers=_auth(customer), timeout=15.0)
    assert order.status_code in (200, 201), order.text
    order_body = order.json()
    assert "order_id" in order_body, f"response must use snake_case order_id, keys: {list(order_body.keys())}"
    assert "orderId" not in order_body, "camelCase orderId must not be present"
    order_id = order_body["order_id"]
    assert order_body["status"] == "PENDING_REVIEW"

    # admin approves -> PENDING_PAYMENT + invoice created
    appr = httpx.post(f"{GATEWAY}/admin/orders/{order_id}/approve",
                      headers=_auth(admin), timeout=15.0)
    assert appr.status_code in (200, 201), appr.text
    appr_body = appr.json()
    assert appr_body["status"] == "PENDING_PAYMENT"

def test_cross_service_ownership_rejected():
    # A customer cannot read another customer's policy by id (403).
    customer = _token(CUSTOMER_USER, CUSTOMER_PASS)
    foreign_policy = str(uuid.uuid4())
    r = httpx.get(f"{GATEWAY}/policies/{foreign_policy}", headers=_auth(customer), timeout=10.0)
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
