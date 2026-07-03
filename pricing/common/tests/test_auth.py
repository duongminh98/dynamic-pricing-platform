import pytest
from httpx import AsyncClient, ASGITransport
from fastapi import Depends, FastAPI

from common.auth import require_administrator, realm_roles, optional_subject
from common.errors import setup_exception_handlers

def gateway_headers(roles, subject='user-subject'):
    return {
        'X-Authenticated-User-Sub': subject,
        'X-Authenticated-User-Roles': ','.join(roles),
        'X-Authenticated-User-Issuer': 'http://localhost:8080/realms/dynamic-pricing',
        'X-Authenticated-Client-Id': 'mini-app',
    }

@pytest.fixture
def app():
    app = FastAPI()
    setup_exception_handlers(app)

    @app.get('/admin/thing')
    async def admin_thing(_claims=Depends(require_administrator)):
        return {'ok': True}

    @app.get('/subject')
    async def subject(request):
        return {'subject': optional_subject(request)}

    return app

@pytest.mark.anyio
async def test_admin_allows_administrator(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url='http://t') as ac:
        r = await ac.get('/admin/thing', headers=gateway_headers(['Administrator']))
    assert r.status_code == 200

@pytest.mark.anyio
async def test_admin_rejects_customer_role(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url='http://t') as ac:
        r = await ac.get('/admin/thing', headers=gateway_headers(['Customer']))
    assert r.status_code == 403
    assert r.json()['error_code'] == 'FORBIDDEN_RESOURCE'

@pytest.mark.anyio
async def test_admin_rejects_missing_gateway_identity(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url='http://t') as ac:
        r = await ac.get('/admin/thing')
    assert r.status_code == 401
    assert r.json()['error_code'] == 'UNAUTHENTICATED'

@pytest.mark.anyio
async def test_bearer_token_without_gateway_identity_is_not_trusted(app):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url='http://t') as ac:
        r = await ac.get('/admin/thing', headers={'Authorization': 'Bearer forged.token.sig'})
    assert r.status_code == 401
    assert r.json()['error_code'] == 'UNAUTHENTICATED'

def test_realm_roles_helper():
    assert realm_roles({'realm_access': {'roles': ['A', 'B']}}) == ['A', 'B']
    assert realm_roles({}) == []
