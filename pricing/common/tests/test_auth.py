import base64
import json

import pytest
from httpx import AsyncClient, ASGITransport
from fastapi import Depends, FastAPI

from common.auth import require_administrator, realm_roles
from common.errors import setup_exception_handlers


def make_token(roles):
    header = base64.urlsafe_b64encode(json.dumps({'alg': 'RS256'}).encode()).decode().rstrip('=')
    payload = base64.urlsafe_b64encode(json.dumps({'realm_access': {'roles': roles}}).encode()).decode().rstrip('=')
    return header + '.' + payload + '.sig'


def auth_header(roles):
    return {'Authorization': 'Bearer ' + make_token(roles)}


@pytest.fixture
def app():
    app = FastAPI()
    setup_exception_handlers(app)

    @app.get('/admin/thing')
    async def admin_thing(_claims=Depends(require_administrator)):
        return {'ok': True}

    return app


@pytest.mark.anyio
async def test_admin_allows_administrator(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url='http://t') as ac:
        r = await ac.get('/admin/thing', headers=auth_header(['Administrator']))
    assert r.status_code == 200


@pytest.mark.anyio
async def test_admin_rejects_customer_role(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url='http://t') as ac:
        r = await ac.get('/admin/thing', headers=auth_header(['Customer']))
    assert r.status_code == 403
    assert r.json()['error_code'] == 'FORBIDDEN_RESOURCE'


@pytest.mark.anyio
async def test_admin_rejects_missing_token(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url='http://t') as ac:
        r = await ac.get('/admin/thing')
    assert r.status_code == 401
    assert r.json()['error_code'] == 'UNAUTHENTICATED'


def test_realm_roles_helper():
    assert realm_roles({'realm_access': {'roles': ['A', 'B']}}) == ['A', 'B']
    assert realm_roles({}) == []
