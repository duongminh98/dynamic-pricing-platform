#!/usr/bin/env python3
"""Render a production Kong declarative config from the dev `kong.yml`.

The dev config targets docker-compose hostnames (`customer-service-1:8080`) and
the local Keycloak issuer (`http://localhost:8080/...`). Production on GKE needs:

  * upstream targets pointing at in-cluster ClusterIP DNS
    (`customer-service.<ns>.svc.cluster.local:<port>`),
  * a single JWT consumer keyed by the public Keycloak issuer
    (the realm pins the RSA key, so the signing key is byte-identical to dev —
    only the `iss` URL changes),
  * CORS locked to the prod SPA origin.

Everything else (routes, header-stripping, the trusted-header post-function) is
reused verbatim so the gateway contract is unchanged. Driven entirely by env
vars so the same renderer serves staging/prod/any domain.
"""
from __future__ import annotations

import copy
import os
import re
import sys
import pathlib

import yaml

HERE = pathlib.Path(__file__).resolve().parent
SRC = HERE / "kong.yml"

# --- parameters (env-overridable) -------------------------------------------
ISSUER = os.environ.get(
    "KONG_ISSUER", "https://auth.dpp-pricing.dev/realms/dynamic-pricing"
)
NAMESPACE = os.environ.get("KONG_NAMESPACE", "dpp")
CLUSTER_DOMAIN = os.environ.get("KONG_CLUSTER_DOMAIN", "svc.cluster.local")
SPA_ORIGIN = os.environ.get("KONG_SPA_ORIGIN", "https://app.dpp-pricing.dev")

# compose target like `customer-service-1:8080` -> service `customer-service`
_TARGET_RE = re.compile(r"^(?P<svc>[a-z0-9-]+?)-\d+:(?P<port>\d+)$")


def cluster_target(compose_target: str) -> str:
    m = _TARGET_RE.match(compose_target)
    if not m:
        raise ValueError(f"unexpected compose target: {compose_target!r}")
    svc = m.group("svc")
    port = m.group("port")
    return f"{svc}.{NAMESPACE}.{CLUSTER_DOMAIN}:{port}"


def transform(doc: dict) -> dict:
    out = copy.deepcopy(doc)

    # 1. JWT consumer: collapse the two dev issuers into one prod issuer,
    #    preserving the (pinned, identical) RSA public key.
    for consumer in out.get("consumers", []):
        secrets = consumer.get("jwt_secrets", [])
        if not secrets:
            continue
        rsa_key = secrets[0]["rsa_public_key"]
        consumer["jwt_secrets"] = [
            {
                "key": ISSUER,
                "algorithm": secrets[0].get("algorithm", "RS256"),
                "rsa_public_key": rsa_key,
            }
        ]

    # 2. CORS origins -> prod SPA only.
    for plugin in out.get("plugins", []):
        if plugin.get("name") == "cors":
            plugin.setdefault("config", {})["origins"] = [SPA_ORIGIN]

    # 3. Upstream targets -> in-cluster ClusterIP DNS.
    for upstream in out.get("upstreams", []):
        for tgt in upstream.get("targets", []):
            tgt["target"] = cluster_target(tgt["target"])

    return out


def main() -> int:
    doc = yaml.safe_load(SRC.read_text(encoding="utf-8"))
    rendered = transform(doc)
    dest = HERE / "kong.prod.yml"
    header = (
        "# GENERATED from kong.yml by render_prod_config.py — do not edit by hand.\n"
        f"# issuer={ISSUER} namespace={NAMESPACE} spa_origin={SPA_ORIGIN}\n"
    )
    dest.write_text(
        header + yaml.safe_dump(rendered, sort_keys=False, allow_unicode=True),
        encoding="utf-8",
    )
    # quick invariants
    text = dest.read_text(encoding="utf-8")
    assert "localhost:8080" not in text, "dev issuer leaked into prod config"
    assert "-1:80" not in text and "-1:8000" not in text, "compose target leaked"
    assert ISSUER in text, "prod issuer missing"
    print(f"wrote {dest} ({len(text.splitlines())} lines)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
