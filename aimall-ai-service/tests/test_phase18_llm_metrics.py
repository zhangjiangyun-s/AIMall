import asyncio

from app.llm.agnes_client import AgnesClient
from app.observability.metrics_registry import agent_metrics


class Response:
    def raise_for_status(self):
        return None

    def json(self):
        return {"choices": [{"message": {"content": "模型回答"}}], "usage": {"prompt_tokens": 12, "completion_tokens": 5}}


class Client:
    is_closed = False

    async def post(self, *args, **kwargs):
        return Response()


def test_llm_chat_records_provider_usage(monkeypatch):
    agent_metrics.reset()
    client = AgnesClient()
    client.api_key = "test-key"
    client.base_url = "http://example.test"
    client.model = "test-model"
    client._client = Client()

    answer = asyncio.run(client.chat("问题", "系统提示"))
    snapshot = agent_metrics.snapshot()

    assert answer == "模型回答"
    assert snapshot["counters"]["llm.calls"] == 1
    assert snapshot["cost"]["inputTokens"] == 12
    assert snapshot["cost"]["outputTokens"] == 5
    assert snapshot["cost"]["usageSource"]["providerCalls"] == 1
    assert snapshot["cost"]["pricingStatus"] == "UNPRICED"
