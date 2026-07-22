from __future__ import annotations

import asyncio
import hashlib
import hmac
import time

from fastapi import HTTPException, Request, status

from app.config.settings import settings
from app.state.redis_backend import redis_state_backend


def canonical_query(raw_query: str) -> str:
    return "&".join(sorted(part for part in raw_query.split("&") if part)) if raw_query else ""


class InternalServiceAuthVerifier:
    _MAX_CLOCK_SKEW_SECONDS = 300

    def __init__(self) -> None:
        self._nonces: dict[str, float] = {}
        self._lock = asyncio.Lock()

    async def verify(self, request: Request) -> None:
        key_id = request.headers.get("X-AIMall-Key-Id", "")
        secret = self._resolve_secret(key_id)
        if not secret:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="内部服务认证失败")
        timestamp_text = request.headers.get("X-AIMall-Timestamp", "")
        nonce = request.headers.get("X-AIMall-Nonce", "")
        signature = request.headers.get("X-AIMall-Signature", "")
        try:
            timestamp = int(timestamp_text)
        except ValueError as exc:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="内部服务认证失败") from exc
        if abs(int(time.time()) - timestamp) > self._MAX_CLOCK_SKEW_SECONDS or len(nonce) < 16 or not signature:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="内部服务认证失败")

        body = await request.body()
        token = request.headers.get("token", "")
        canonical = (
            f"{request.method.upper()}\n{request.url.path}\n{canonical_query(request.url.query)}\n"
            f"{hashlib.sha256(body).hexdigest()}\n{hashlib.sha256(token.encode('utf-8')).hexdigest()}\n"
            f"{key_id}\n{timestamp_text}\n{nonce}"
        )
        expected = hmac.new(secret.encode("utf-8"), canonical.encode("utf-8"), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(expected, signature):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="内部服务认证失败")
        if not await self._reserve_nonce(key_id, nonce):
            raise HTTPException(status_code=status.HTTP_409_CONFLICT, detail="内部服务请求已被使用")

    def _resolve_secret(self, key_id: str) -> str:
        if key_id == settings.JAVA_TO_AI_CURRENT_KEY_ID:
            return settings.JAVA_TO_AI_CURRENT_SECRET
        if key_id and key_id == settings.JAVA_TO_AI_PREVIOUS_KEY_ID:
            return settings.JAVA_TO_AI_PREVIOUS_SECRET
        return ""

    async def _reserve_nonce(self, key_id: str, nonce: str) -> bool:
        nonce_key = f"{key_id}:{nonce}"
        if redis_state_backend.enabled:
            try:
                return await redis_state_backend.set_if_absent(
                    f"{settings.STATE_KEY_PREFIX}:internal-ai-nonce:{nonce_key}",
                    "1",
                    self._MAX_CLOCK_SKEW_SECONDS,
                )
            except Exception as exc:
                raise HTTPException(
                    status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                    detail="内部服务防重放存储不可用",
                ) from exc
        now = time.time()
        async with self._lock:
            self._nonces = {key: expires_at for key, expires_at in self._nonces.items() if expires_at > now}
            if nonce_key in self._nonces:
                return False
            self._nonces[nonce_key] = now + self._MAX_CLOCK_SKEW_SECONDS
            return True


internal_service_auth_verifier = InternalServiceAuthVerifier()


async def require_internal_service(request: Request) -> None:
    await internal_service_auth_verifier.verify(request)
