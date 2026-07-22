from __future__ import annotations

import asyncio
import hashlib
import json
from collections import defaultdict
from typing import Any

from app.observability.metrics_registry import agent_metrics


class SseLimitMiddleware:
    def __init__(
        self,
        app,
        *,
        max_global: int,
        max_per_client: int,
        max_duration_seconds: int,
    ) -> None:
        self.app = app
        self.max_global = max(1, max_global)
        self.max_per_client = max(1, max_per_client)
        self.max_duration_seconds = max(0.01, float(max_duration_seconds))
        self._lock = asyncio.Lock()
        self._global_count = 0
        self._client_counts: dict[str, int] = defaultdict(int)

    async def __call__(self, scope: dict[str, Any], receive, send) -> None:
        if scope.get("type") != "http" or scope.get("path") != "/ai/chat":
            await self.app(scope, receive, send)
            return
        client_key = self._client_key(scope)
        if not await self._acquire(client_key):
            agent_metrics.increment("sse.rejected")
            await self._send_error(send, 429, "SSE_CONCURRENCY_LIMIT")
            return

        response_started = False

        async def tracked_send(message: dict[str, Any]) -> None:
            nonlocal response_started
            if message.get("type") == "http.response.start":
                response_started = True
            await send(message)

        try:
            await asyncio.wait_for(
                self.app(scope, receive, tracked_send),
                timeout=self.max_duration_seconds,
            )
        except TimeoutError:
            agent_metrics.increment("sse.timeouts")
            if response_started:
                await send({"type": "http.response.body", "body": b"", "more_body": False})
            else:
                await self._send_error(send, 504, "SSE_MAX_DURATION_EXCEEDED")
        finally:
            await self._release(client_key)

    async def _acquire(self, client_key: str) -> bool:
        async with self._lock:
            if self._global_count >= self.max_global:
                return False
            if self._client_counts[client_key] >= self.max_per_client:
                return False
            self._global_count += 1
            self._client_counts[client_key] += 1
            agent_metrics.set_gauge("sse.active", self._global_count)
            return True

    async def _release(self, client_key: str) -> None:
        async with self._lock:
            self._global_count = max(0, self._global_count - 1)
            remaining = self._client_counts.get(client_key, 0) - 1
            if remaining <= 0:
                self._client_counts.pop(client_key, None)
            else:
                self._client_counts[client_key] = remaining
            agent_metrics.set_gauge("sse.active", self._global_count)

    def _client_key(self, scope: dict[str, Any]) -> str:
        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        token = headers.get(b"token", b"")
        if token:
            return "token:" + hashlib.sha256(token).hexdigest()
        client = scope.get("client") or ("unknown", 0)
        return f"ip:{client[0]}"

    async def _send_error(self, send, status_code: int, error_code: str) -> None:
        body = json.dumps(
            {"code": 1, "message": error_code, "data": {"errorCode": error_code}},
            separators=(",", ":"),
        ).encode("utf-8")
        await send({
            "type": "http.response.start",
            "status": status_code,
            "headers": [(b"content-type", b"application/json; charset=utf-8")],
        })
        await send({"type": "http.response.body", "body": body, "more_body": False})
