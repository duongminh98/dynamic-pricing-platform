"""
Trusted gateway identity and role helpers for FastAPI services.

Kong is the only component that validates JWT signature, expiry, and issuer at
the public boundary. After validation it strips client-supplied identity headers
and injects the trusted headers consumed here. This module performs service-level
authorization from those headers; it does not parse or verify bearer tokens.
"""

from __future__ import annotations

from typing import Any, Dict

from fastapi import Request

from .errors import ErrorCode, ServiceException

SUBJECT_HEADER = "x-authenticated-user-sub"
ROLES_HEADER = "x-authenticated-user-roles"
ISSUER_HEADER = "x-authenticated-user-issuer"
CLIENT_ID_HEADER = "x-authenticated-client-id"

def gateway_roles(request: Request) -> list[str]:
    roles = request.headers.get(ROLES_HEADER, "")
    return [role.strip() for role in roles.split(",") if role.strip()]

def gateway_claims(request: Request) -> Dict[str, Any]:
    subject = request.headers.get(SUBJECT_HEADER, "").strip()
    roles = gateway_roles(request)
    if not subject:
        raise ServiceException(ErrorCode.UNAUTHENTICATED, "Missing gateway identity")
    return {
        "sub": subject,
        "realm_access": {"roles": roles},
        "iss": request.headers.get(ISSUER_HEADER, "").strip(),
        "azp": request.headers.get(CLIENT_ID_HEADER, "").strip(),
    }

def realm_roles(claims: Dict[str, Any]) -> list[str]:
    realm_access = claims.get("realm_access") or {}
    roles = realm_access.get("roles") or []
    return [str(role) for role in roles]

def require_role(required_role: str):
    """Build a FastAPI dependency enforcing a trusted gateway realm role."""

    async def _dependency(request: Request) -> Dict[str, Any]:
        claims = gateway_claims(request)
        if required_role not in realm_roles(claims):
            raise ServiceException(ErrorCode.FORBIDDEN_RESOURCE,
                                   f"Requires role {required_role}")
        return claims

    return _dependency

require_administrator = require_role("Administrator")

def optional_subject(request: Request) -> str | None:
    subject = request.headers.get(SUBJECT_HEADER, "").strip()
    return subject or None
