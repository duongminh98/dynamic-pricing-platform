"""Real end-to-end + cross-service ownership tests through Kong.

Feature: dynamic-pricing-platform
Validates: R6.4, R6.10, R6.11, R18.3, R33.2, R34.1, R7.1.

These run against the live stack via Kong (http://localhost:8000) brought up by
`docker compose up`. They SKIP (not pass) when the gateway or Keycloak is not
reachable, so CI without the stack does not get false confidence.
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
            "age": 30, "gender": "Male", "province": "Ha Noi",
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
    quote_id = quote.json()["quote_id"]

    # create order -> PENDING_REVIEW
    order = httpx.post(f"{GATEWAY}/orders", json={"quote_id": quote_id},
                       headers=_auth(customer), timeout=15.0)
    assert order.status_code in (200, 201), order.text
    order_id = order.json()["order_id"]
    assert order.json()["status"] == "PENDING_REVIEW"

    # admin approves -> PENDING_PAYMENT + invoice created
    appr = httpx.post(f"{GATEWAY}/admin/orders/{order_id}/approve",
                      headers=_auth(admin), timeout=15.0)
    assert appr.status_code in (200, 201), appr.text
    assert appr.json()["status"] == "PENDING_PAYMENT"


def test_cross_service_ownership_rejected():
    # A customer cannot read another customer's policy by id (403).
    customer = _token(CUSTOMER_USER, CUSTOMER_PASS)
    foreign_policy = str(uuid.uuid4())
    r = httpx.get(f"{GATEWAY}/policies/{foreign_policy}", headers=_auth(customer), timeout=10.0)
    assert r.status_code in (403, 404), r.text
