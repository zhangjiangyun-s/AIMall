import asyncio
import json

from app.security.sse_limiter import SseLimitMiddleware


def _scope(client: str = "127.0.0.1"):
    return {
        "type": "http",
        "path": "/ai/chat",
        "client": (client, 1234),
        "headers": [],
    }


async def _receive():
    return {"type": "http.disconnect"}


def _collector(messages):
    async def send(message):
        messages.append(message)
    return send


def test_concurrency_limit_releases_after_completion():
    entered = asyncio.Event()
    release = asyncio.Event()

    async def app(_scope, _receive, send):
        entered.set()
        await release.wait()
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"ok", "more_body": False})

    middleware = SseLimitMiddleware(app, max_global=1, max_per_client=1, max_duration_seconds=5)

    async def scenario():
        first_messages = []
        first = asyncio.create_task(middleware(_scope(), _receive, _collector(first_messages)))
        await entered.wait()
        rejected = []
        await middleware(_scope("127.0.0.2"), _receive, _collector(rejected))
        assert rejected[0]["status"] == 429
        release.set()
        await first
        third = []
        await middleware(_scope("127.0.0.2"), _receive, _collector(third))
        assert third[0]["status"] == 200

    asyncio.run(scenario())


def test_timeout_closes_stream_and_releases_lease():
    async def slow_app(_scope, _receive, _send):
        await asyncio.sleep(1)

    middleware = SseLimitMiddleware(slow_app, max_global=1, max_per_client=1, max_duration_seconds=0.01)

    async def scenario():
        messages = []
        await middleware(_scope(), _receive, _collector(messages))
        assert messages[0]["status"] == 504
        payload = json.loads(messages[1]["body"])
        assert payload["data"]["errorCode"] == "SSE_MAX_DURATION_EXCEEDED"
        assert middleware._global_count == 0

    asyncio.run(scenario())
