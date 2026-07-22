import json
import logging
import asyncio

import pytest
from fastapi import HTTPException

from app.api import metrics_api
from app.observability.logging_config import JsonLogFormatter
from app.observability.middleware import TraceContextMiddleware
from app.observability.trace_context import current_client_trace_id, reset_trace_id, set_trace_id
from app.tools.java_client import JavaClient


def test_observability_token_rejects_missing_credentials(monkeypatch):
    monkeypatch.setattr(metrics_api.settings, "OBSERVABILITY_TOKEN", "o" * 40)
    monkeypatch.setattr(metrics_api.settings, "AIMALL_ENVIRONMENT", "prod")

    with pytest.raises(HTTPException) as error:
        metrics_api.require_observability_token(None, None)

    assert error.value.status_code == 401


def test_prometheus_snapshot_contains_operational_metrics_without_user_content(monkeypatch):
    monkeypatch.setattr(metrics_api.settings, "OBSERVABILITY_TOKEN", "")
    monkeypatch.setattr(metrics_api.settings, "AIMALL_ENVIRONMENT", "local")
    monkeypatch.setattr(metrics_api.redis_state_backend, "metrics", _async_value({
        "status": "UP", "poolMaxConnections": 100, "poolInUseConnections": 7, "poolUtilization": 0.07,
    }))
    monkeypatch.setattr(metrics_api.pending_action_store, "status_counts", _async_value({"PENDING": 2}))
    monkeypatch.setattr(metrics_api.milvus_store, "health", lambda: {"status": "UP", "rowCount": 7})

    response = asyncio.run(metrics_api.prometheus_metrics(None))
    body = response.body.decode("utf-8")

    assert "aimall_ai_pending_actions{status=\"PENDING\"} 2.0" in body
    assert "aimall_ai_milvus_vectors 7.0" in body
    assert "aimall_ai_redis_pool_utilization 0.07" in body
    assert "message" not in body
    assert "sessionId" not in body


def test_trace_middleware_propagates_header_to_response_and_java_client():
    captured = {}

    async def app(scope, receive, send):
        captured.update(JavaClient(base_url="http://java")._request_headers("GET", "/internal/ai/test", "", b""))
        captured["clientTraceId"] = current_client_trace_id()
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b"ok"})

    messages = []

    async def send(message):
        messages.append(message)

    async def receive():
        return {"type": "http.request", "body": b"", "more_body": False}

    middleware = TraceContextMiddleware(app)
    asyncio.run(
        middleware(
            {
                "type": "http",
                "path": "/test",
                "headers": [(b"x-trace-id", b"stage9-cross-service")],
            },
            receive,
            send,
        )
    )

    assert captured["X-Trace-Id"] != "stage9-cross-service"
    assert captured["clientTraceId"] == "stage9-cross-service"
    start = next(message for message in messages if message["type"] == "http.response.start")
    assert (b"x-trace-id", captured["X-Trace-Id"].encode("ascii")) in start["headers"]


def test_json_log_formatter_emits_trace_and_masks_secret_fields():
    context_token = set_trace_id("stage9-log-trace")
    try:
        record = logging.LogRecord(
            "test", logging.INFO, __file__, 1,
            "password=hunter2 Bearer secret-token phone=13800138000", (), None,
        )
        payload = json.loads(JsonLogFormatter().format(record))
    finally:
        reset_trace_id(context_token)

    assert payload["traceId"] == "stage9-log-trace"
    assert payload["service"] == "aimall-ai-service"
    assert "hunter2" not in payload["message"]
    assert "secret-token" not in payload["message"]
    assert "13800138000" not in payload["message"]


def _async_value(value):
    async def result(*_args, **_kwargs):
        return value

    return result
