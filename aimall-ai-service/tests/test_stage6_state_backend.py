import asyncio

import pytest
from types import SimpleNamespace

from main import state_unavailable_handler
from app.config.settings import settings
from app.state import redis_backend as redis_backend_module
from app.state.redis_backend import AiStateUnavailableError, RedisStateBackend


def run(coro):
    return asyncio.run(coro)


class FailingRedisClient:
    async def get(self, _key):
        raise ConnectionError("redis unavailable")


def test_redis_failure_uses_explicit_ai_state_unavailable_error():
    backend = object.__new__(RedisStateBackend)
    backend.enabled = True
    backend._client = FailingRedisClient()

    with pytest.raises(AiStateUnavailableError) as error:
        run(backend.get_json("aimall:state:v1:session:test"))

    assert error.value.code == "AI_STATE_UNAVAILABLE"


def test_ai_state_unavailable_http_contract_is_503():
    response = run(state_unavailable_handler(None, AiStateUnavailableError("down")))

    assert response.status_code == 503
    assert b'"errorCode":"AI_STATE_UNAVAILABLE"' in response.body


def test_redis_client_uses_bounded_connection_and_socket_timeouts(monkeypatch):
    captured = {}

    def from_url(url, **kwargs):
        captured["url"] = url
        captured.update(kwargs)
        return object()

    monkeypatch.setattr(settings, "STATE_BACKEND", "redis")
    monkeypatch.setattr(redis_backend_module, "redis_async", SimpleNamespace(from_url=from_url))

    RedisStateBackend()

    assert captured["socket_connect_timeout"] == settings.REDIS_CONNECT_TIMEOUT_SECONDS
    assert captured["socket_timeout"] == settings.REDIS_SOCKET_TIMEOUT_SECONDS
