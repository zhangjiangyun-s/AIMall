import asyncio

import httpx

from app.config.settings import settings, validate_model_routing_settings
from app.llm.agnes_client import AgnesClient
from app.llm.model_router import ModelPurpose, model_router
from app.observability.metrics_registry import agent_metrics


def configure_models(monkeypatch, *, routing=True, fast="fast-model", primary="primary-model", fallback="fallback-model"):
    monkeypatch.setattr(settings, "MODEL_ROUTING_ENABLED", routing)
    monkeypatch.setattr(settings, "AGNES_MODEL", "legacy-model")
    monkeypatch.setattr(settings, "AGNES_FAST_MODEL", fast)
    monkeypatch.setattr(settings, "AGNES_PRIMARY_MODEL", primary)
    monkeypatch.setattr(settings, "AGNES_FALLBACK_MODEL", fallback)


def test_router_sends_complex_planning_and_generation_to_primary_model(monkeypatch):
    configure_models(monkeypatch)

    assert model_router.route(ModelPurpose.PLANNING).attempted_models == ["primary-model", "fallback-model"]
    assert model_router.route(ModelPurpose.SUMMARY).selected_model == "fast-model"
    assert model_router.route(ModelPurpose.JUDGE).selected_model == "fast-model"
    assert model_router.route(ModelPurpose.GENERATION).attempted_models == ["primary-model", "fallback-model"]


def test_router_uses_legacy_model_when_routing_is_disabled(monkeypatch):
    configure_models(monkeypatch, routing=False)

    assert model_router.route(ModelPurpose.GENERATION).attempted_models == ["legacy-model"]


class Response:
    def __init__(self, answer="ok"):
        self.answer = answer

    def raise_for_status(self):
        return None

    def json(self):
        return {"choices": [{"message": {"content": self.answer}}], "usage": {"prompt_tokens": 10, "completion_tokens": 2}}


class FallbackClient:
    is_closed = False

    def __init__(self):
        self.models = []

    async def post(self, *args, **kwargs):
        model = kwargs["json"]["model"]
        self.models.append(model)
        if model == "primary-model":
            request = httpx.Request("POST", "http://example.test/chat/completions")
            raise httpx.ConnectError("primary unavailable", request=request)
        return Response("fallback answer")


def test_client_falls_back_once_and_records_model_attribution(monkeypatch):
    configure_models(monkeypatch)
    agent_metrics.reset()
    transport = FallbackClient()
    client = AgnesClient()
    client.api_key = "test-key"
    client.base_url = "http://example.test"
    client.model = "legacy-model"
    client._client = transport

    answer = asyncio.run(client.chat("question", "system", purpose=ModelPurpose.GENERATION))
    snapshot = agent_metrics.snapshot()

    assert answer == "fallback answer"
    assert transport.models == ["primary-model", "fallback-model"]
    assert snapshot["counters"]["llm.purpose.GENERATION.calls"] == 2
    assert snapshot["counters"]["llm.model.primary-model.failures"] == 1
    assert snapshot["counters"]["llm.fallback.calls"] == 1


def test_duplicate_fallback_is_not_retried(monkeypatch):
    configure_models(monkeypatch, primary="same-model", fallback="same-model")

    assert model_router.route(ModelPurpose.GENERATION).attempted_models == ["same-model"]


class NonRetryableClient:
    is_closed = False

    def __init__(self):
        self.models = []

    async def post(self, *args, **kwargs):
        request = httpx.Request("POST", "http://example.test/chat/completions")
        self.models.append(kwargs["json"]["model"])
        response = httpx.Response(401, request=request)
        raise httpx.HTTPStatusError("unauthorized", request=request, response=response)


def test_client_does_not_fallback_for_non_retryable_provider_errors(monkeypatch):
    configure_models(monkeypatch)
    transport = NonRetryableClient()
    client = AgnesClient()
    client.api_key = "test-key"
    client.base_url = "http://example.test"
    client._client = transport

    try:
        asyncio.run(client.chat("question", "system", purpose=ModelPurpose.GENERATION))
    except RuntimeError as exc:
        assert "primary-model" in str(exc)
        assert "fallback-model" not in str(exc)
    else:
        raise AssertionError("non-retryable provider error must fail")
    assert transport.models == ["primary-model"]


def test_model_routing_configuration_rejects_empty_primary(monkeypatch):
    configure_models(monkeypatch, primary="")

    try:
        validate_model_routing_settings()
    except RuntimeError as exc:
        assert "AGNES_PRIMARY_MODEL" in str(exc)
    else:
        raise AssertionError("empty primary model must be rejected")


def test_model_routing_configuration_rejects_partial_prices(monkeypatch):
    configure_models(monkeypatch)
    monkeypatch.setattr(settings, "AGNES_PRIMARY_INPUT_COST_PER_MILLION_USD", 1.0)
    monkeypatch.setattr(settings, "AGNES_PRIMARY_OUTPUT_COST_PER_MILLION_USD", 0.0)

    try:
        validate_model_routing_settings()
    except RuntimeError as exc:
        assert "AGNES_PRIMARY" in str(exc)
    else:
        raise AssertionError("partial model prices must be rejected")


class StreamResponse:
    async def __aenter__(self):
        return self

    async def __aexit__(self, *_args):
        return False

    def raise_for_status(self):
        return None

    async def aiter_lines(self):
        yield 'data: {"choices":[{"delta":{"content":"hello"}}]}'
        yield 'data: {"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":2}}'
        yield "data: [DONE]"


class StreamClient:
    is_closed = False

    def stream(self, *_args, **_kwargs):
        return StreamResponse()


def test_stream_records_provider_usage_and_model_attribution(monkeypatch):
    configure_models(monkeypatch)
    agent_metrics.reset()
    client = AgnesClient()
    client.api_key = "test-key"
    client.base_url = "http://example.test"
    client._client = StreamClient()

    async def collect():
        return "".join([part async for part in client.stream_chat("question", "system")])

    assert asyncio.run(collect()) == "hello"
    snapshot = agent_metrics.snapshot()
    assert snapshot["cost"]["inputTokens"] == 7
    assert snapshot["cost"]["outputTokens"] == 2
    assert snapshot["cost"]["usageSource"]["providerCalls"] == 1
    assert snapshot["counters"]["llm.model.primary-model.calls"] == 1
