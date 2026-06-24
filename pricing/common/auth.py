"""
JWT role-based access control for FastAPI services (R18.1, R18.2, R37.4, R37.5).

The API gateway (Kong) verifies the JWT signature and expiry, but does not check
roles. Service endpoints that require a specific realm role enforce it here by
reading ``realm_access.roles`` from the bearer token. The token is decoded
without re-verifying the signature (already verified at the gateway); this module
only performs authorization, not authentication of the signature.
"""

from __future__ import annotations

import base64
import binascii
import json
from typing import Any, Dict

from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from .errors import ErrorCode, ServiceException

_bearer = HTTPBearer(auto_error=False)


def _decode_payload(token: str) -> Dict[str, Any]:
    parts = token.split(".")
    if len(parts) != 3:
        raise ServiceException(ErrorCode.UNAUTHENTICATED, "Malformed bearer token")
    payload_b64 = parts[1]
    padding = "=" * (-len(payload_b64) % 4)
    try:
        decoded = base64.urlsafe_b64decode(payload_b64 + padding)
        return json.loads(decoded)
    except (ValueError, binascii.Error, json.JSONDecodeError) as exc:
        raise ServiceException(ErrorCode.UNAUTHENTICATED, "Unreadable bearer token") from exc


def realm_roles(claims: Dict[str, Any]) -> list[str]:
    realm_access = claims.get("realm_access") or {}
    roles = realm_access.get("roles") or []
    return [str(r) for r in roles]


def require_role(required_role: str):
    """Build a FastAPI dependency enforcing a realm role; 401 if no token, 403 if missing role."""

    async def _dependency(
        request: Request,
        credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
    ) -> Dict[str, Any]:
        if credentials is None or not credentials.credentials:
            raise ServiceException(ErrorCode.UNAUTHENTICATED, "Missing bearer token")
        claims = _decode_payload(credentials.credentials)
        if required_role not in realm_roles(claims):
            raise ServiceException(ErrorCode.FORBIDDEN_RESOURCE,
                                   f"Requires role {required_role}")
        return claims

    return _dependency


# Convenience dependency for Administrator-only routes.
require_administrator = require_role("Administrator")
