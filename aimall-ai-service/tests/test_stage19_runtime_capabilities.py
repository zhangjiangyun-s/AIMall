import asyncio
import pytest

from app.config.settings import resolve_ai_runtime_mode, settings
from app.memory.session_memory import SessionMemoryStore
from app.runtime import runtime_capabilities
from app.state.redis_backend import AiStateUnavailableError
from app.tools.executor import ToolExecutor


class FailingBackend:
    enabled = True

    async def get_json(self, _key):
        from app.state.redis_backend import AiStateUnavailableError
        raise AiStateUnavailableError("down")


def test_capability_matrix_and_hash_are_stable(monkeypatch):
    expected = {
        "MOCK": (False, "SIMULATED", "DISABLED"),
        "RULE_BASED": (False, "REAL_READ_ONLY", "DISABLED"),
        "LLM": (True, "REAL_READ_ONLY", "CONFIRMATION_REQUIRED"),
        "SANDBOX": (True, "REAL_READ_ONLY", "SANDBOX"),
        "PRODUCTION": (True, "REAL_READ_ONLY", "REAL_CONFIRMATION_PERMISSION"),
    }
    for mode, values in expected.items():
        monkeypatch.setattr(settings, "AI_RUNTIME_MODE", mode)
        capabilities = runtime_capabilities.values()
        assert (capabilities["llm"], capabilities["readTools"], capabilities["writeTools"]) == values
        assert runtime_capabilities.capability_hash() == runtime_capabilities.capability_hash()


def test_runtime_mode_rollout_is_stable_and_supports_rollback():
    assert resolve_ai_runtime_mode("PRODUCTION", "LLM", 0, "instance-a") == "LLM"
    assert resolve_ai_runtime_mode("PRODUCTION", "LLM", 100, "instance-a") == "PRODUCTION"
    first = resolve_ai_runtime_mode("PRODUCTION", "LLM", 37, "instance-a")
    assert first == resolve_ai_runtime_mode("PRODUCTION", "LLM", 37, "instance-a")


def test_read_only_memory_degrades_to_stateless_when_redis_fails():
    store = SessionMemoryStore(backend=FailingBackend())

    context, degraded = asyncio.run(store.get_read_only("session-1", "token"))

    assert degraded is True
    assert context["sessionId"] == "session-1"
    assert context["turnCount"] == 0


def test_write_tool_propagates_state_unavailable_instead_of_returning_soft_failure(monkeypatch):
    async def fail_closed(*_args, **_kwargs):
        raise AiStateUnavailableError("redis unavailable")

    executor = ToolExecutor()
    monkeypatch.setattr(executor, "_create_pending_action", fail_closed)

    with pytest.raises(AiStateUnavailableError):
        asyncio.run(executor.execute(
            "add_to_cart_confirmed",
            {"productId": 1, "quantity": 1},
            "trace-stage19-state",
            auth_token="token",
            session_id="session-stage19",
        ))
