import asyncio
import hashlib
import hmac
import time

import pytest
import httpx
from fastapi import HTTPException
from starlette.requests import Request

from app.config.settings import settings
from app.security.internal_auth import InternalServiceAuthVerifier
from app.state.redis_backend import redis_state_backend
from main import app


def _request(method: str, path: str, query: str, body: bytes, headers: dict[str, str]) -> Request:
    sent = False

    async def receive():
        nonlocal sent
        if sent:
            return {"type": "http.request", "body": b"", "more_body": False}
        sent = True
        return {"type": "http.request", "body": body, "more_body": False}

    scope = {
        "type": "http",
        "http_version": "1.1",
        "method": method,
        "scheme": "http",
        "path": path,
        "raw_path": path.encode(),
        "query_string": query.encode(),
        "headers": [(key.lower().encode(), value.encode()) for key, value in headers.items()],
        "client": ("127.0.0.1", 12345),
        "server": ("localhost", 8000),
    }
    return Request(scope, receive)


def _signed_headers(
    method: str,
    path: str,
    query: str,
    body: bytes,
    secret: str,
    nonce: str,
    key_id: str = "test-key",
) -> dict[str, str]:
    timestamp = str(int(time.time()))
    canonical_query = "&".join(sorted(part for part in query.split("&") if part))
    canonical = (
        f"{method}\n{path}\n{canonical_query}\n{hashlib.sha256(body).hexdigest()}\n"
        f"{hashlib.sha256(b'').hexdigest()}\n{key_id}\n{timestamp}\n{nonce}"
    )
    return {
        "X-AIMall-Key-Id": key_id,
        "X-AIMall-Timestamp": timestamp,
        "X-AIMall-Nonce": nonce,
        "X-AIMall-Signature": hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest(),
    }


def test_missing_signature_is_rejected(monkeypatch):
    monkeypatch.setattr(settings, "JAVA_TO_AI_CURRENT_KEY_ID", "test-key")
    monkeypatch.setattr(settings, "JAVA_TO_AI_CURRENT_SECRET", "test-secret")
    monkeypatch.setattr(redis_state_backend, "enabled", False)
    verifier = InternalServiceAuthVerifier()

    with pytest.raises(HTTPException) as error:
        asyncio.run(verifier.verify(_request("POST", "/ai/vector/sync", "", b"{}", {})))

    assert error.value.status_code == 401


def test_body_tampering_is_rejected(monkeypatch):
    secret = "test-secret"
    monkeypatch.setattr(settings, "JAVA_TO_AI_CURRENT_KEY_ID", "test-key")
    monkeypatch.setattr(settings, "JAVA_TO_AI_CURRENT_SECRET", secret)
    monkeypatch.setattr(redis_state_backend, "enabled", False)
    verifier = InternalServiceAuthVerifier()
    headers = _signed_headers("POST", "/ai/vector/sync", "limit=10", b"{}", secret, "a" * 32)

    with pytest.raises(HTTPException) as error:
        asyncio.run(verifier.verify(_request("POST", "/ai/vector/sync", "limit=10", b'{"changed":true}', headers)))

    assert error.value.status_code == 401


def test_valid_signature_is_accepted_once_and_replay_is_rejected(monkeypatch):
    secret = "test-secret"
    monkeypatch.setattr(settings, "JAVA_TO_AI_CURRENT_KEY_ID", "test-key")
    monkeypatch.setattr(settings, "JAVA_TO_AI_CURRENT_SECRET", secret)
    monkeypatch.setattr(redis_state_backend, "enabled", False)
    verifier = InternalServiceAuthVerifier()
    body = b"{}"
    headers = _signed_headers("POST", "/ai/vector/sync", "limit=10", body, secret, "b" * 32)

    asyncio.run(verifier.verify(_request("POST", "/ai/vector/sync", "limit=10", body, headers)))
    with pytest.raises(HTTPException) as error:
        asyncio.run(verifier.verify(_request("POST", "/ai/vector/sync", "limit=10", body, headers)))

    assert error.value.status_code == 409


def test_all_non_health_routes_are_protected_at_asgi_boundary(monkeypatch):
    monkeypatch.setattr(settings, "JAVA_TO_AI_CURRENT_KEY_ID", "test-key")
    monkeypatch.setattr(settings, "JAVA_TO_AI_CURRENT_SECRET", "test-secret")
    monkeypatch.setattr(redis_state_backend, "enabled", False)

    async def call_routes():
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            health_response = await client.get("/health")
            integration_response = await client.get("/health/integration")
            vector_response = await client.get("/ai/vector/health")
            chat_response = await client.post("/ai/chat", json={})
            model_response = await client.get("/health/model")
            return health_response, integration_response, vector_response, chat_response, model_response

    health, integration, vector, chat, model = asyncio.run(call_routes())
    assert health.status_code == 200
    assert integration.status_code == 401
    assert vector.status_code == 401
    assert chat.status_code == 401
    assert model.status_code == 401


def test_previous_key_is_accepted_during_rotation(monkeypatch):
    monkeypatch.setattr(settings, "JAVA_TO_AI_CURRENT_KEY_ID", "current")
    monkeypatch.setattr(settings, "JAVA_TO_AI_CURRENT_SECRET", "current-secret")
    monkeypatch.setattr(settings, "JAVA_TO_AI_PREVIOUS_KEY_ID", "previous")
    monkeypatch.setattr(settings, "JAVA_TO_AI_PREVIOUS_SECRET", "previous-secret")
    monkeypatch.setattr(redis_state_backend, "enabled", False)
    verifier = InternalServiceAuthVerifier()
    body = b"{}"
    headers = _signed_headers(
        "POST", "/ai/vector/sync", "b=2&a=1", body, "previous-secret", "c" * 32, "previous"
    )

    asyncio.run(verifier.verify(_request("POST", "/ai/vector/sync", "b=2&a=1", body, headers)))
