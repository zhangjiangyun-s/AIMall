from __future__ import annotations

import asyncio
import json
import uuid
import time
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

from app.config.settings import settings

try:
    import redis.asyncio as redis_async
except ImportError:  # Unit tests can use the explicit in-memory backend.
    redis_async = None


class AiStateUnavailableError(RuntimeError):
    code = "AI_STATE_UNAVAILABLE"


class RedisStateBackend:
    def __init__(self) -> None:
        self.enabled = settings.STATE_BACKEND == "redis"
        if self.enabled and redis_async is None:
            raise RuntimeError("STATE_BACKEND=redis requires the redis package")
        self._client = redis_async.from_url(
            settings.REDIS_URL,
            decode_responses=True,
            socket_connect_timeout=settings.REDIS_CONNECT_TIMEOUT_SECONDS,
            socket_timeout=settings.REDIS_SOCKET_TIMEOUT_SECONDS,
            max_connections=settings.REDIS_MAX_CONNECTIONS,
        ) if self.enabled else None

    async def get_json(self, key: str) -> dict[str, Any] | None:
        if not self._client:
            return None
        value = await self._execute(self._client.get(key))
        return json.loads(value) if value else None

    async def set_json(self, key: str, value: dict[str, Any], ttl_seconds: int) -> None:
        if self._client:
            await self._execute(self._client.set(key, json.dumps(value, ensure_ascii=False, default=str), ex=max(1, ttl_seconds)))

    async def set_if_absent(self, key: str, value: str, ttl_seconds: int) -> bool:
        if not self._client:
            return False
        return bool(await self._execute(self._client.set(key, value, ex=max(1, ttl_seconds), nx=True)))

    async def get(self, key: str) -> str | None:
        return await self._execute(self._client.get(key)) if self._client else None

    async def delete(self, *keys: str) -> int:
        if not self._client or not keys:
            return 0
        return int(await self._execute(self._client.delete(*keys)))

    async def add_to_set(self, key: str, value: str, ttl_seconds: int) -> None:
        if not self._client:
            return
        async with self._client.pipeline(transaction=True) as pipeline:
            pipeline.sadd(key, value)
            pipeline.expire(key, max(1, ttl_seconds))
            await self._execute(pipeline.execute())

    async def set_members(self, key: str) -> set[str]:
        if not self._client:
            return set()
        return set(await self._execute(self._client.smembers(key)))

    @asynccontextmanager
    async def lock(self, key: str, ttl_seconds: int = 30) -> AsyncIterator[None]:
        if not self._client:
            yield
            return
        token = uuid.uuid4().hex
        lock_key = f"{settings.STATE_KEY_PREFIX}:lock:{key}"
        deadline = asyncio.get_running_loop().time() + 5
        while not await self._execute(self._client.set(lock_key, token, ex=max(1, ttl_seconds), nx=True)):
            if asyncio.get_running_loop().time() >= deadline:
                raise RuntimeError("distributed state lock timeout")
            await asyncio.sleep(0.05)
        try:
            yield
        finally:
            await self._execute(self._client.eval(
                "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                1,
                lock_key,
                token,
            ))

    async def _execute(self, operation):
        try:
            return await operation
        except Exception as exc:
            raise AiStateUnavailableError("AI state backend is unavailable") from exc

    async def close(self) -> None:
        if self._client:
            await self._client.aclose()

    async def health(self) -> dict[str, Any]:
        if not self.enabled:
            return {"enabled": False, "status": "DISABLED"}
        try:
            ready = bool(await self._client.ping())
            return {"enabled": True, "status": "UP" if ready else "DOWN"}
        except Exception as exc:
            return {"enabled": True, "status": "DOWN", "message": str(exc)[:200]}

    async def metrics(self) -> dict[str, int | float | str | bool]:
        if not self.enabled:
            return {"enabled": False, "status": "DISABLED"}
        try:
            started = time.perf_counter()
            info = await self._execute(self._client.info())
            latency_ms = (time.perf_counter() - started) * 1000
            pool = self._client.connection_pool
            max_connections = max(1, int(getattr(pool, "max_connections", settings.REDIS_MAX_CONNECTIONS)))
            in_use_connections = len(getattr(pool, "_in_use_connections", ()))
            return {
                "enabled": True,
                "status": "UP",
                "connectedClients": int(info.get("connected_clients") or 0),
                "usedMemoryBytes": int(info.get("used_memory") or 0),
                "expiredKeys": int(info.get("expired_keys") or 0),
                "keyspaceHits": int(info.get("keyspace_hits") or 0),
                "keyspaceMisses": int(info.get("keyspace_misses") or 0),
                "latencyMs": round(latency_ms, 3),
                "poolMaxConnections": max_connections,
                "poolInUseConnections": in_use_connections,
                "poolUtilization": round(in_use_connections / max_connections, 6),
            }
        except Exception:
            return {"enabled": True, "status": "DOWN"}

    async def status_counts(self, pattern: str, limit: int = 10_000) -> dict[str, int]:
        if not self._client:
            return {}
        counts: dict[str, int] = {}
        scanned = 0
        async for key in self._client.scan_iter(match=pattern, count=200):
            payload = await self.get_json(key)
            status = str((payload or {}).get("status") or "UNKNOWN")
            counts[status] = counts.get(status, 0) + 1
            scanned += 1
            if scanned >= limit:
                break
        return counts


redis_state_backend = RedisStateBackend()
