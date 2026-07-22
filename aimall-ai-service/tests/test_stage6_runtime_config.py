from app.config.settings import resolve_redis_url


def test_redis_url_uses_explicit_configuration(monkeypatch):
    monkeypatch.setenv("REDIS_URL", "redis://cache.internal:6379/2")

    assert resolve_redis_url() == "redis://cache.internal:6379/2"


def test_local_redis_url_reuses_encoded_internal_secret(monkeypatch):
    monkeypatch.delenv("REDIS_URL", raising=False)
    monkeypatch.delenv("REDIS_PASSWORD", raising=False)
    monkeypatch.setenv("AIMALL_INTERNAL_API_SECRET", "secret with:/symbols")

    assert resolve_redis_url() == "redis://:secret%20with%3A%2Fsymbols@127.0.0.1:6379/0"


def test_local_redis_url_prefers_dedicated_password(monkeypatch):
    monkeypatch.delenv("REDIS_URL", raising=False)
    monkeypatch.setenv("REDIS_PASSWORD", "redis-only")
    monkeypatch.setenv("AIMALL_INTERNAL_API_SECRET", "internal-secret")

    assert resolve_redis_url() == "redis://:redis-only@127.0.0.1:6379/0"
