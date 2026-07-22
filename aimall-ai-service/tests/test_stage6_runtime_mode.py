import asyncio

import pytest

from app.api.health_api import model_health
from app.config.settings import settings
from app.llm.agnes_client import AgnesClient


@pytest.mark.parametrize("mode", ["MOCK", "RULE_BASED"])
def test_non_llm_runtime_modes_never_call_external_model(monkeypatch, mode):
    monkeypatch.setattr(settings, "AI_RUNTIME_MODE", mode)
    client = AgnesClient()
    client.api_key = "configured-but-must-not-be-used"

    assert client.external_calls_allowed is False
    assert client.enabled is False
    with pytest.raises(RuntimeError, match=f"AI_RUNTIME_MODE={mode}"):
        asyncio.run(client.chat("question", "system"))


@pytest.mark.parametrize("mode", ["LLM", "SANDBOX", "PRODUCTION"])
def test_llm_runtime_modes_allow_configured_external_model(monkeypatch, mode):
    monkeypatch.setattr(settings, "AI_RUNTIME_MODE", mode)
    client = AgnesClient()
    client.api_key = "configured"
    client.base_url = "https://example.invalid/v1"
    client.model = "test-model"

    assert client.external_calls_allowed is True
    assert client.enabled is True


def test_model_health_reports_disabled_without_consuming_model_quota(monkeypatch):
    monkeypatch.setattr(settings, "AI_RUNTIME_MODE", "RULE_BASED")

    result = asyncio.run(model_health())

    assert result["status"] == "DISABLED"
    assert result["runtimeMode"] == "RULE_BASED"
    assert result["model"] is None
