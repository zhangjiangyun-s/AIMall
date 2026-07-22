import asyncio
import json

from app.tools.java_client import JavaClient


class FakeResponse:
    def raise_for_status(self):
        return None

    def json(self):
        return {"code": 0, "message": "success", "data": {}}


class FakeHttpClient:
    is_closed = False

    def __init__(self):
        self.requests = []

    async def post(self, url, *, content, headers):
        self.requests.append((url, content, headers))
        return FakeResponse()

    async def aclose(self):
        self.is_closed = True


def test_all_task_writebacks_include_signed_execution_fence():
    client = JavaClient(base_url="http://server")
    fake = FakeHttpClient()
    client._client = fake
    client.bind_knowledge_execution("KT-FENCE-1", "execution-token-1")

    asyncio.run(
        client.update_knowledge_task_status(
            "KT-FENCE-1", status="RUNNING", current_step="parse_started"
        )
    )

    body = json.loads(fake.requests[0][1].decode("utf-8"))
    assert body["executionTaskId"] == "KT-FENCE-1"
    assert body["executionToken"] == "execution-token-1"
    assert fake.requests[0][2]["Content-Type"] == "application/json"
