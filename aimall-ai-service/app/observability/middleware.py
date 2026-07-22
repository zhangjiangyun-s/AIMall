from __future__ import annotations

from typing import Any

from app.observability.trace_context import (
    new_trace_id,
    reset_client_trace_id,
    reset_trace_id,
    resolve_client_trace_id,
    set_client_trace_id,
    set_trace_id,
)


class TraceContextMiddleware:
    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope: dict[str, Any], receive, send) -> None:
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return
        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        candidate = headers.get(b"x-trace-id", b"").decode("ascii", errors="ignore")
        trace_id = new_trace_id()
        client_trace_id = resolve_client_trace_id(candidate)
        context_token = set_trace_id(trace_id)
        client_context_token = set_client_trace_id(client_trace_id)

        async def traced_send(message: dict[str, Any]) -> None:
            if message.get("type") == "http.response.start":
                response_headers = list(message.get("headers", []))
                response_headers.append((b"x-trace-id", trace_id.encode("ascii")))
                message["headers"] = response_headers
            await send(message)

        try:
            await self.app(scope, receive, traced_send)
        finally:
            reset_client_trace_id(client_context_token)
            reset_trace_id(context_token)
