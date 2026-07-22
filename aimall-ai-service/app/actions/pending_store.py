from __future__ import annotations

import asyncio
import hashlib
import json
import os
import time
import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Awaitable, Callable

from app.config.settings import settings
from app.guardrails import guardrail_service
from app.state import redis_state_backend


SUPPORTED_ACTIONS = {
    "ADD_TO_CART",
    "CLAIM_COUPON",
    "CANCEL_ORDER",
    "APPLY_RETURN",
}
TERMINAL_STATUSES = {"SUCCEEDED", "FAILED", "REJECTED", "EXPIRED", "CLEARED"}


class PendingActionError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


class RetryableActionError(RuntimeError):
    pass


@dataclass(slots=True)
class PendingAction:
    action_id: str
    action_type: str
    owner_fingerprint: str
    session_id: str
    arguments: dict[str, Any]
    title: str
    summary: str
    trace_id: str
    status: str = "PENDING"
    created_at: float = field(default_factory=time.time)
    expires_at: float = 0.0
    updated_at: float = 0.0
    result: Any = None
    error: str | None = None
    execution_count: int = 0
    execution_token: str | None = None
    action_version: int = 1
    schema_version: str = "1"
    tenant_id: str = "default"
    user_id: int | None = None
    business_object_id: str | None = None
    operation: str = ""
    permission_snapshot: str = ""


class ActionAuditLogger:
    def __init__(self, log_dir: str | None = None):
        self.log_dir = Path(log_dir or settings.ACTION_AUDIT_LOG_DIR)
        self._lock = asyncio.Lock()

    async def write(self, action: PendingAction, event: str) -> None:
        payload = guardrail_service.sanitize_payload({
            "timestamp": datetime.now().astimezone().isoformat(timespec="milliseconds"),
            "event": event,
            "actionId": action.action_id,
            "actionType": action.action_type,
            "status": action.status,
            "ownerFingerprint": action.owner_fingerprint,
            "sessionIdHash": hashlib.sha256(action.session_id.encode("utf-8")).hexdigest()[:16],
            "traceId": action.trace_id,
            "tenantId": action.tenant_id,
            "userId": action.user_id,
            "actionVersion": action.action_version,
            "schemaVersion": action.schema_version,
            "businessObjectId": action.business_object_id,
            "operation": action.operation,
            "permissionSnapshot": action.permission_snapshot,
            "arguments": action.arguments,
            "executionCount": action.execution_count,
            "error": action.error,
        })
        async with self._lock:
            await asyncio.to_thread(self._append, payload)

    def _append(self, payload: dict[str, Any]) -> None:
        self.log_dir.mkdir(parents=True, exist_ok=True)
        cutoff = datetime.now().astimezone() - timedelta(days=max(1, settings.AUDIT_RETENTION_DAYS))
        for old_path in self.log_dir.glob("*.jsonl"):
            try:
                if datetime.fromtimestamp(old_path.stat().st_mtime).astimezone() < cutoff:
                    old_path.unlink()
            except OSError:
                continue
        path = self.log_dir / f"{datetime.now().astimezone():%Y-%m-%d}.jsonl"
        with path.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(payload, ensure_ascii=False, default=str) + "\n")
            stream.flush()
            os.fsync(stream.fileno())


class PendingActionStore:
    def __init__(
        self,
        *,
        ttl_seconds: int | None = None,
        execution_lease_seconds: int | None = None,
        result_ttl_seconds: int | None = None,
        max_count: int | None = None,
        clock: Callable[[], float] = time.time,
        audit_logger: ActionAuditLogger | None = None,
        backend=redis_state_backend,
    ):
        self.ttl_seconds = ttl_seconds or settings.PENDING_ACTION_TTL_SECONDS
        self.execution_lease_seconds = execution_lease_seconds or settings.PENDING_ACTION_EXECUTION_LEASE_SECONDS
        self.result_ttl_seconds = result_ttl_seconds or settings.PENDING_ACTION_RESULT_TTL_SECONDS
        self.max_count = max_count or settings.PENDING_ACTION_MAX_COUNT
        self.clock = clock
        self.audit_logger = audit_logger or ActionAuditLogger()
        self.backend = backend
        self._actions: dict[str, PendingAction] = {}
        self._lock = asyncio.Lock()

    async def create(
        self,
        *,
        action_type: str,
        arguments: dict[str, Any],
        title: str,
        summary: str,
        session_id: str,
        auth_token: str | None,
        trace_id: str,
        tenant_id: str = "default",
        user_id: int | None = None,
    ) -> dict[str, Any]:
        if self.backend.enabled:
            return await self._create_persistent(
                action_type=action_type,
                arguments=arguments,
                title=title,
                summary=summary,
                session_id=session_id,
                auth_token=auth_token,
                trace_id=trace_id,
                tenant_id=tenant_id,
                user_id=user_id,
            )
        owner = self._owner(auth_token)
        normalized_type = action_type.strip().upper()
        if normalized_type not in SUPPORTED_ACTIONS:
            raise PendingActionError("ACTION_TYPE_UNSUPPORTED", "不支持的确认操作")
        self._validate_arguments(arguments)
        now = self.clock()
        fingerprint = self._proposal_fingerprint(
            owner, session_id, normalized_type, arguments, tenant_id, user_id
        )
        async with self._lock:
            self._expire_locked(now)
            for action in self._actions.values():
                if action.status == "PENDING" and self._action_fingerprint(action) == fingerprint:
                    return self._public(action, reused=True)
            if len(self._actions) >= self.max_count:
                removable = [item for item in self._actions.values() if item.status in TERMINAL_STATUSES]
                target = min(removable or self._actions.values(), key=lambda item: item.updated_at or item.created_at)
                self._actions.pop(target.action_id, None)
            action = PendingAction(
                action_id=str(uuid.uuid4()),
                action_type=normalized_type,
                owner_fingerprint=owner,
                session_id=session_id,
                arguments=dict(arguments),
                title=title.strip(),
                summary=summary.strip(),
                trace_id=trace_id,
                created_at=now,
                updated_at=now,
                expires_at=now + self.ttl_seconds,
                tenant_id=tenant_id,
                user_id=user_id,
                business_object_id=self._business_object_id(arguments),
                operation=normalized_type,
                permission_snapshot=self._permission_snapshot(
                    owner, session_id, normalized_type, arguments, tenant_id, user_id, 1
                ),
            )
            self._actions[action.action_id] = action
        await self.audit_logger.write(action, "CREATED")
        return self._public(action)

    async def confirm(
        self,
        action_id: str,
        *,
        session_id: str,
        auth_token: str | None,
        execute: Callable[[PendingAction], Awaitable[Any]],
        tenant_id: str = "default",
        user_id: int | None = None,
        action_version: int = 1,
    ) -> dict[str, Any]:
        if self.backend.enabled:
            return await self._confirm_persistent(
                action_id,
                session_id=session_id,
                auth_token=auth_token,
                execute=execute,
                tenant_id=tenant_id,
                user_id=user_id,
                action_version=action_version,
            )
        async with self._lock:
            action = self._require_owned_locked(action_id, session_id, auth_token)
            self._validate_context(action, tenant_id, user_id, action_version)
            if action.status == "SUCCEEDED":
                return self._public(action, replayed=True)
            if action.status == "EXECUTING":
                raise PendingActionError("ACTION_EXECUTING", "操作正在执行，请勿重复提交")
            if action.status != "PENDING":
                raise PendingActionError(f"ACTION_{action.status}", "当前操作已不可确认")
            if action.expires_at <= self.clock():
                action.status = "EXPIRED"
                action.updated_at = self.clock()
                raise PendingActionError("ACTION_EXPIRED", "确认操作已过期，请重新发起")
            action.status = "EXECUTING"
            action.updated_at = self.clock()
            action.expires_at = max(action.expires_at, action.updated_at + self.execution_lease_seconds)
            action.execution_count += 1
            execution_token = str(uuid.uuid4())
            action.execution_token = execution_token
        await self.audit_logger.write(action, "EXECUTING")
        try:
            result = await execute(action)
        except Exception as exc:
            async with self._lock:
                if action.status != "EXECUTING" or action.execution_token != execution_token:
                    return self._public(action)
                action.status = "PENDING" if isinstance(exc, RetryableActionError) else "FAILED"
                action.error = str(exc)
                action.updated_at = self.clock()
                action.execution_token = None
            await self.audit_logger.write(
                action,
                "RETRYABLE_FAILED" if isinstance(exc, RetryableActionError) else "FAILED",
            )
            return self._public(action)
        async with self._lock:
            if action.status != "EXECUTING" or action.execution_token != execution_token:
                return self._public(action)
            action.status = "SUCCEEDED"
            action.result = result
            action.updated_at = self.clock()
            action.execution_token = None
        await self.audit_logger.write(action, "SUCCEEDED")
        return self._public(action)

    async def reject(self, action_id: str, *, session_id: str, auth_token: str | None,
                     tenant_id: str = "default", user_id: int | None = None, action_version: int = 1) -> dict[str, Any]:
        if self.backend.enabled:
            async with self.backend.lock(f"pending:{action_id}"):
                action = await self._require_persistent(action_id, session_id, auth_token)
                self._validate_context(action, tenant_id, user_id, action_version)
                if action.status == "REJECTED":
                    return self._public(action, replayed=True)
                if action.status != "PENDING":
                    raise PendingActionError("ACTION_NOT_REJECTABLE", "当前操作已不可取消")
                action.status = "REJECTED"
                action.updated_at = self.clock()
                await self._save_persistent(action)
            await self.audit_logger.write(action, "REJECTED")
            return self._public(action)
        async with self._lock:
            action = self._require_owned_locked(action_id, session_id, auth_token)
            self._validate_context(action, tenant_id, user_id, action_version)
            if action.status == "REJECTED":
                return self._public(action, replayed=True)
            if action.status != "PENDING":
                raise PendingActionError("ACTION_NOT_REJECTABLE", "当前操作已不可取消")
            action.status = "REJECTED"
            action.updated_at = self.clock()
        await self.audit_logger.write(action, "REJECTED")
        return self._public(action)

    async def get(self, action_id: str, *, session_id: str, auth_token: str | None,
                  tenant_id: str = "default", user_id: int | None = None, action_version: int = 1) -> dict[str, Any]:
        if self.backend.enabled:
            action = await self._require_persistent(action_id, session_id, auth_token)
            self._validate_context(action, tenant_id, user_id, action_version)
            return self._public(action)
        async with self._lock:
            action = self._require_owned_locked(action_id, session_id, auth_token)
            self._validate_context(action, tenant_id, user_id, action_version)
            return self._public(action)

    async def clear_session(self, session_id: str, auth_token: str | None) -> int:
        owner = self._owner(auth_token)
        if self.backend.enabled:
            index_key = self._session_index_key(owner, session_id)
            action_ids = await self.backend.set_members(index_key)
            if action_ids:
                await self.backend.delete(*(self._action_key(action_id) for action_id in action_ids))
            await self.backend.delete(index_key)
            return len(action_ids)
        async with self._lock:
            action_ids = [
                action_id
                for action_id, action in self._actions.items()
                if action.owner_fingerprint == owner and action.session_id == session_id
            ]
            for action_id in action_ids:
                self._actions.pop(action_id, None)
            return len(action_ids)

    async def close(self) -> None:
        async with self._lock:
            self._actions.clear()

    async def status_counts(self) -> dict[str, int]:
        if self.backend.enabled:
            return await self.backend.status_counts(
                f"{settings.STATE_KEY_PREFIX}:pending:action:*",
                self.max_count,
            )
        async with self._lock:
            self._expire_locked(self.clock())
            counts: dict[str, int] = {}
            for action in self._actions.values():
                counts[action.status] = counts.get(action.status, 0) + 1
            return counts

    async def _create_persistent(
        self,
        *,
        action_type: str,
        arguments: dict[str, Any],
        title: str,
        summary: str,
        session_id: str,
        auth_token: str | None,
        trace_id: str,
        tenant_id: str = "default",
        user_id: int | None = None,
    ) -> dict[str, Any]:
        owner = self._owner(auth_token)
        normalized_type = action_type.strip().upper()
        if normalized_type not in SUPPORTED_ACTIONS:
            raise PendingActionError("ACTION_TYPE_UNSUPPORTED", "不支持的确认操作")
        self._validate_arguments(arguments)
        fingerprint = self._proposal_fingerprint(
            owner, session_id, normalized_type, arguments, tenant_id, user_id
        )
        fingerprint_key = f"{settings.STATE_KEY_PREFIX}:pending:fingerprint:{fingerprint}"
        async with self.backend.lock(f"pending-fingerprint:{fingerprint}"):
            existing_id = await self.backend.get(fingerprint_key)
            if existing_id:
                existing = await self._load_persistent(existing_id)
                if existing and existing.status == "PENDING" and existing.expires_at > self.clock():
                    return self._public(existing, reused=True)
                await self.backend.delete(fingerprint_key)
            now = self.clock()
            action = PendingAction(
                action_id=str(uuid.uuid4()),
                action_type=normalized_type,
                owner_fingerprint=owner,
                session_id=session_id,
                arguments=dict(arguments),
                title=title.strip(),
                summary=summary.strip(),
                trace_id=trace_id,
                created_at=now,
                updated_at=now,
                expires_at=now + self.ttl_seconds,
                tenant_id=tenant_id,
                user_id=user_id,
                business_object_id=self._business_object_id(arguments),
                operation=normalized_type,
                permission_snapshot=self._permission_snapshot(
                    owner, session_id, normalized_type, arguments, tenant_id, user_id, 1
                ),
            )
            await self._save_persistent(action)
            await self.backend.set_if_absent(fingerprint_key, action.action_id, self.ttl_seconds)
            await self.backend.add_to_set(
                self._session_index_key(owner, session_id),
                action.action_id,
                self.ttl_seconds,
            )
        await self.audit_logger.write(action, "CREATED")
        return self._public(action)

    async def _confirm_persistent(
        self,
        action_id: str,
        *,
        session_id: str,
        auth_token: str | None,
        execute: Callable[[PendingAction], Awaitable[Any]],
        tenant_id: str = "default",
        user_id: int | None = None,
        action_version: int = 1,
    ) -> dict[str, Any]:
        async with self.backend.lock(f"pending:{action_id}"):
            action = await self._require_persistent(action_id, session_id, auth_token)
            self._validate_context(action, tenant_id, user_id, action_version)
            if action.status == "SUCCEEDED":
                return self._public(action, replayed=True)
            if action.status == "EXECUTING" and action.updated_at > self.clock() - self.execution_lease_seconds:
                raise PendingActionError("ACTION_EXECUTING", "操作正在执行，请勿重复提交")
            if action.status == "EXECUTING":
                action.status = "PENDING"
            if action.status != "PENDING":
                raise PendingActionError(f"ACTION_{action.status}", "当前操作已不可确认")
            if action.expires_at <= self.clock():
                action.status = "EXPIRED"
                action.updated_at = self.clock()
                await self._save_persistent(action)
                raise PendingActionError("ACTION_EXPIRED", "确认操作已过期，请重新发起")
            action.status = "EXECUTING"
            action.updated_at = self.clock()
            action.expires_at = max(action.expires_at, action.updated_at + self.execution_lease_seconds)
            action.execution_count += 1
            execution_token = str(uuid.uuid4())
            action.execution_token = execution_token
            await self._save_persistent(action)
        await self.audit_logger.write(action, "EXECUTING")
        try:
            result = await execute(action)
        except Exception as exc:
            async with self.backend.lock(f"pending:{action_id}"):
                current = await self._load_persistent(action_id)
                if current is None:
                    action.status = "CLEARED"
                    action.result = None
                    action.error = None
                    action.updated_at = self.clock()
                    action.execution_token = None
                    await self.audit_logger.write(action, "CLEARED_AFTER_EXECUTION")
                    return self._public(action)
                if current.status != "EXECUTING" or current.execution_token != execution_token:
                    return self._public(current)
                current.status = "PENDING" if isinstance(exc, RetryableActionError) else "FAILED"
                current.error = str(exc)
                current.updated_at = self.clock()
                current.execution_token = None
                await self._save_persistent(current)
            await self.audit_logger.write(current, "RETRYABLE_FAILED" if isinstance(exc, RetryableActionError) else "FAILED")
            return self._public(current)
        async with self.backend.lock(f"pending:{action_id}"):
            current = await self._load_persistent(action_id)
            if current is None:
                action.status = "CLEARED"
                action.result = None
                action.error = None
                action.updated_at = self.clock()
                action.execution_token = None
                await self.audit_logger.write(action, "CLEARED_AFTER_EXECUTION")
                return self._public(action)
            if current.status != "EXECUTING" or current.execution_token != execution_token:
                return self._public(current)
            current.status = "SUCCEEDED"
            current.result = result
            current.updated_at = self.clock()
            current.execution_token = None
            await self._save_persistent(current)
        await self.audit_logger.write(current, "SUCCEEDED")
        return self._public(current)

    async def _require_persistent(
        self,
        action_id: str,
        session_id: str,
        auth_token: str | None,
    ) -> PendingAction:
        action = await self._load_persistent(action_id)
        if action is None:
            raise PendingActionError("ACTION_NOT_FOUND", "确认操作不存在")
        if action.owner_fingerprint != self._owner(auth_token) or action.session_id != session_id:
            raise PendingActionError("ACTION_FORBIDDEN", "无权访问该确认操作")
        return action

    async def _load_persistent(self, action_id: str) -> PendingAction | None:
        payload = await self.backend.get_json(self._action_key(action_id))
        return PendingAction(**payload) if payload else None

    async def _save_persistent(self, action: PendingAction) -> None:
        now = self.clock()
        if action.status in TERMINAL_STATUSES:
            ttl = max(1, self.result_ttl_seconds)
        elif action.status == "EXECUTING":
            ttl = max(1, int(max(action.expires_at, now + self.execution_lease_seconds) - now))
        else:
            ttl = max(1, int(action.expires_at - now))
        await self.backend.set_json(self._action_key(action.action_id), asdict(action), ttl)
        await self.backend.add_to_set(
            self._session_index_key(action.owner_fingerprint, action.session_id),
            action.action_id,
            ttl,
        )

    def _action_key(self, action_id: str) -> str:
        return f"{settings.STATE_KEY_PREFIX}:pending:action:{action_id}"

    def _session_index_key(self, owner: str, session_id: str) -> str:
        digest = hashlib.sha256(f"{owner}:{session_id}".encode("utf-8")).hexdigest()
        return f"{settings.STATE_KEY_PREFIX}:pending:session:{digest}"

    def _require_owned_locked(self, action_id: str, session_id: str, auth_token: str | None) -> PendingAction:
        action = self._actions.get(action_id)
        if action is None:
            raise PendingActionError("ACTION_NOT_FOUND", "确认操作不存在")
        if action.owner_fingerprint != self._owner(auth_token) or action.session_id != session_id:
            raise PendingActionError("ACTION_FORBIDDEN", "无权访问该确认操作")
        if action.status == "PENDING" and action.expires_at <= self.clock():
            action.status = "EXPIRED"
            action.updated_at = self.clock()
        return action

    def _expire_locked(self, now: float) -> None:
        for action in self._actions.values():
            if action.status == "PENDING" and action.expires_at <= now:
                action.status = "EXPIRED"
                action.updated_at = now

    def _owner(self, auth_token: str | None) -> str:
        if not auth_token:
            raise PendingActionError("AUTH_REQUIRED", "确认式写操作需要登录")
        return hashlib.sha256(auth_token.encode("utf-8")).hexdigest()[:24]

    def _validate_arguments(self, arguments: dict[str, Any]) -> None:
        forbidden = {"userId", "memberId", "token", "authToken"}
        if forbidden.intersection(arguments):
            raise PendingActionError("UNSAFE_ARGUMENT", "动作参数不能包含用户身份或 Token")

    def _business_object_id(self, arguments: dict[str, Any]) -> str | None:
        for key in ("orderId", "productId", "couponId", "productSkuId"):
            if arguments.get(key) is not None:
                return f"{key}:{arguments[key]}"
        return None

    def _permission_snapshot(
        self,
        owner: str,
        session_id: str,
        operation: str,
        arguments: dict[str, Any],
        tenant_id: str,
        user_id: int | None,
        action_version: int,
    ) -> str:
        raw = json.dumps(
            [owner, tenant_id, user_id, session_id, operation, self._business_object_id(arguments), action_version],
            ensure_ascii=False,
            sort_keys=True,
            default=str,
        )
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()

    def _validate_context(self, action: PendingAction, tenant_id: str, user_id: int | None, action_version: int) -> None:
        if action.tenant_id != tenant_id or action.action_version != action_version:
            raise PendingActionError("ACTION_CONTEXT_MISMATCH", "确认操作上下文不匹配")
        if action.user_id is not None and action.user_id != user_id:
            raise PendingActionError("ACTION_FORBIDDEN", "无权访问该确认操作")

        expected_snapshot = self._permission_snapshot(
            action.owner_fingerprint,
            action.session_id,
            action.operation,
            action.arguments,
            tenant_id,
            user_id,
            action_version,
        )
        if action.permission_snapshot != expected_snapshot:
            raise PendingActionError("ACTION_PERMISSION_CHANGED", "Action permission context is no longer valid")

    def _proposal_fingerprint(
        self,
        owner: str,
        session_id: str,
        action_type: str,
        arguments: dict[str, Any],
        tenant_id: str = "default",
        user_id: int | None = None,
    ) -> str:
        raw = json.dumps(
            [owner, tenant_id, user_id, session_id, action_type, arguments],
            ensure_ascii=False,
            sort_keys=True,
            default=str,
        )
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()

    def _action_fingerprint(self, action: PendingAction) -> str:
        return self._proposal_fingerprint(
            action.owner_fingerprint,
            action.session_id,
            action.action_type,
            action.arguments,
            action.tenant_id,
            action.user_id,
        )

    def _public(self, action: PendingAction, *, reused: bool = False, replayed: bool = False) -> dict[str, Any]:
        payload = asdict(action)
        payload.pop("owner_fingerprint", None)
        payload.pop("arguments", None)
        payload["actionId"] = payload.pop("action_id")
        payload["actionType"] = payload.pop("action_type")
        payload.pop("session_id", None)
        payload["traceId"] = payload.pop("trace_id")
        payload["createdAt"] = payload.pop("created_at")
        payload["expiresAt"] = payload.pop("expires_at")
        payload["updatedAt"] = payload.pop("updated_at")
        payload["executionCount"] = payload.pop("execution_count")
        payload["actionVersion"] = payload.pop("action_version")
        payload["schemaVersion"] = payload.pop("schema_version")
        payload["tenantId"] = payload.pop("tenant_id")
        payload["userId"] = payload.pop("user_id")
        payload["businessObjectId"] = payload.pop("business_object_id")
        payload["permissionSnapshot"] = payload.pop("permission_snapshot")
        payload.pop("execution_token", None)
        payload["reused"] = reused
        payload["replayed"] = replayed
        payload["retryable"] = action.status == "PENDING" and bool(action.error)
        return payload


pending_action_store = PendingActionStore()
