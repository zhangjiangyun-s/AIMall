from __future__ import annotations

import asyncio
import hashlib
import json
import re
import time
from dataclasses import asdict, dataclass, field
from typing import Any, Callable

from app.config.settings import settings
from app.guardrails import guardrail_service
from app.memory.summarizer import SESSION_SUMMARY_VERSION, SessionSummarizer, session_summarizer
from app.schemas.tool_schema import ToolCallRecord
from app.state import redis_state_backend
from app.state.redis_backend import AiStateUnavailableError

SESSION_RECORD_SCHEMA_VERSION = "1"


@dataclass(slots=True)
class MemoryEntity:
    kind: str
    entity_id: str
    label: str
    status: str = ""
    source_tool: str = ""
    ordinal: int = 0


@dataclass(slots=True)
class MemoryTurn:
    user_message: str
    assistant_answer: str
    intent: str
    trace_id: str
    tool_summaries: list[str] = field(default_factory=list)
    entities: list[MemoryEntity] = field(default_factory=list)
    created_at: float = field(default_factory=time.time)


@dataclass(slots=True)
class SessionRecord:
    schema_version: str = SESSION_RECORD_SCHEMA_VERSION
    owner_fingerprint: str = ""
    tenant_id: str = "default"
    user_id: int | None = None
    session_id: str = ""
    turns: list[MemoryTurn] = field(default_factory=list)
    expires_at: float = 0.0
    summary: str = ""
    summary_version: str = SESSION_SUMMARY_VERSION
    summary_updated_at: float = 0.0
    summary_entities: list[MemoryEntity] = field(default_factory=list)
    total_turns: int = 0
    compression_count: int = 0
    summary_fallback_used: bool = False


class SessionMemoryStore:
    def __init__(
        self,
        *,
        ttl_seconds: int | None = None,
        max_turns: int | None = None,
        max_sessions: int | None = None,
        context_token_budget: int | None = None,
        summarizer: SessionSummarizer | None = None,
        clock: Callable[[], float] = time.time,
        backend=redis_state_backend,
    ):
        self.ttl_seconds = ttl_seconds or settings.SESSION_MEMORY_TTL_SECONDS
        self.max_turns = max_turns or settings.SESSION_MEMORY_MAX_TURNS
        self.max_sessions = max_sessions or settings.SESSION_MEMORY_MAX_SESSIONS
        self.context_token_budget = context_token_budget or settings.SESSION_MEMORY_CONTEXT_TOKEN_BUDGET
        self.summarizer = summarizer or session_summarizer
        self.clock = clock
        self.backend = backend
        self._sessions: dict[str, SessionRecord] = {}
        self._lock = asyncio.Lock()
        self._session_locks: dict[str, asyncio.Lock] = {}

    async def get(
        self,
        session_id: str,
        auth_token: str | None,
        *,
        tenant_id: str = "default",
        user_id: int | None = None,
    ) -> dict[str, Any]:
        key = self._key(session_id, auth_token, tenant_id, user_id)
        if self.backend.enabled:
            record = await self._load_persistent(key)
            if record is not None and not self._matches_context(
                record, session_id, auth_token, tenant_id, user_id
            ):
                await self.backend.delete(self._storage_key(key))
                record = None
            if record is None or record.expires_at <= self.clock():
                if record is not None:
                    await self.backend.delete(self._storage_key(key))
                return self._empty_context(session_id)
            return self._context(session_id, record)
        async with self._lock:
            self._cleanup_locked()
            record = self._sessions.get(key)
            if record is None:
                return self._empty_context(session_id)
            return self._context(session_id, record)

    def empty_context(self, session_id: str) -> dict[str, Any]:
        return self._empty_context(session_id)

    async def get_read_only(
        self,
        session_id: str,
        auth_token: str | None,
        *,
        tenant_id: str = "default",
        user_id: int | None = None,
    ) -> tuple[dict[str, Any], bool]:
        try:
            return await self.get(
                session_id, auth_token, tenant_id=tenant_id, user_id=user_id
            ), False
        except AiStateUnavailableError:
            return self._empty_context(session_id), True

    async def append(
        self,
        *,
        session_id: str,
        auth_token: str | None,
        user_message: str,
        assistant_answer: str,
        intent: str,
        trace_id: str,
        tool_calls: list[ToolCallRecord],
        tenant_id: str = "default",
        user_id: int | None = None,
    ) -> dict[str, Any]:
        key = self._key(session_id, auth_token, tenant_id, user_id)
        if self.backend.enabled:
            async with self.backend.lock(f"session:{key}", ttl_seconds=60):
                record = await self._load_persistent(key)
                if record is not None and not self._matches_context(
                    record, session_id, auth_token, tenant_id, user_id
                ):
                    await self.backend.delete(self._storage_key(key))
                    record = None
                async with self._lock:
                    if record is not None:
                        self._sessions[key] = record
                    else:
                        self._sessions.pop(key, None)
                context = await self._append_in_memory(
                    session_id=session_id,
                    auth_token=auth_token,
                    user_message=user_message,
                    assistant_answer=assistant_answer,
                    intent=intent,
                    trace_id=trace_id,
                    tool_calls=tool_calls,
                    tenant_id=tenant_id,
                    user_id=user_id,
                )
                async with self._lock:
                    current = self._sessions.pop(key, None)
                    self._session_locks.pop(key, None)
                if current is not None:
                    await self._save_persistent(key, current)
                return context
        return await self._append_in_memory(
            session_id=session_id,
            auth_token=auth_token,
            user_message=user_message,
            assistant_answer=assistant_answer,
            intent=intent,
            trace_id=trace_id,
            tool_calls=tool_calls,
            tenant_id=tenant_id,
            user_id=user_id,
        )

    async def _append_in_memory(
        self,
        *,
        session_id: str,
        auth_token: str | None,
        user_message: str,
        assistant_answer: str,
        intent: str,
        trace_id: str,
        tool_calls: list[ToolCallRecord],
        tenant_id: str = "default",
        user_id: int | None = None,
    ) -> dict[str, Any]:
        key = self._key(session_id, auth_token, tenant_id, user_id)
        now = self.clock()
        turn = MemoryTurn(
            user_message=self._trim(
                guardrail_service.redact_text(user_message)[0],
                settings.SESSION_MEMORY_MAX_USER_CHARS,
            ),
            assistant_answer=self._trim(
                guardrail_service.redact_text(assistant_answer)[0],
                settings.SESSION_MEMORY_MAX_ASSISTANT_CHARS,
            ),
            intent=intent,
            trace_id=trace_id,
            tool_summaries=self._tool_summaries(tool_calls),
            entities=self._extract_entities(tool_calls)[: settings.SESSION_MEMORY_MAX_ENTITIES_PER_TURN],
            created_at=now,
        )
        session_lock = await self._session_lock(key)
        async with session_lock:
            async with self._lock:
                self._cleanup_locked()
                if key not in self._sessions and len(self._sessions) >= self.max_sessions:
                    oldest_key = min(self._sessions, key=lambda item: self._sessions[item].expires_at)
                    self._sessions.pop(oldest_key, None)
                    self._session_locks.pop(oldest_key, None)
                record = self._sessions.setdefault(
                    key,
                    SessionRecord(
                        owner_fingerprint=self._owner(auth_token),
                        tenant_id=tenant_id,
                        user_id=user_id,
                        session_id=session_id,
                    ),
                )
                record.turns.append(turn)
                record.total_turns += 1
                evicted = record.turns[:-self.max_turns] if len(record.turns) > self.max_turns else []
                record.turns = record.turns[-self.max_turns :]
                record.expires_at = now + self.ttl_seconds
                previous_summary = record.summary

            if evicted:
                summary, fallback_used = await self.summarizer.summarize(
                    previous_summary,
                    [self._turn_dict(item) for item in evicted],
                )
                async with self._lock:
                    if self._sessions.get(key) is record:
                        record.summary = summary
                        record.summary_entities = self._merge_entities(
                            [entity for item in evicted for entity in item.entities],
                            record.summary_entities,
                            settings.SESSION_MEMORY_MAX_SUMMARY_ENTITIES,
                        )
                        record.summary_updated_at = self.clock()
                        record.compression_count += 1
                        record.summary_fallback_used = fallback_used

            async with self._lock:
                current = self._sessions.get(key)
                return self._context(session_id, current) if current else self._empty_context(session_id)

    async def clear(
        self,
        session_id: str,
        auth_token: str | None,
        *,
        tenant_id: str = "default",
        user_id: int | None = None,
    ) -> bool:
        key = self._key(session_id, auth_token, tenant_id, user_id)
        if self.backend.enabled:
            return await self.backend.delete(self._storage_key(key)) > 0
        async with self._lock:
            removed = self._sessions.pop(key, None) is not None
            self._session_locks.pop(key, None)
            return removed

    async def close(self) -> None:
        async with self._lock:
            self._sessions.clear()
            self._session_locks.clear()

    async def _load_persistent(self, key: str) -> SessionRecord | None:
        payload = await self.backend.get_json(self._storage_key(key))
        if not payload:
            return None
        turns = [
            MemoryTurn(
                user_message=item.get("user_message", ""),
                assistant_answer=item.get("assistant_answer", ""),
                intent=item.get("intent", ""),
                trace_id=item.get("trace_id", ""),
                tool_summaries=list(item.get("tool_summaries") or []),
                entities=[MemoryEntity(**entity) for entity in item.get("entities") or []],
                created_at=float(item.get("created_at") or 0),
            )
            for item in payload.get("turns") or []
        ]
        return SessionRecord(
            schema_version=str(payload.get("schema_version") or ""),
            owner_fingerprint=str(payload.get("owner_fingerprint") or ""),
            tenant_id=str(payload.get("tenant_id") or ""),
            user_id=int(payload["user_id"]) if payload.get("user_id") is not None else None,
            session_id=str(payload.get("session_id") or ""),
            turns=turns,
            expires_at=float(payload.get("expires_at") or 0),
            summary=str(payload.get("summary") or ""),
            summary_version=str(payload.get("summary_version") or SESSION_SUMMARY_VERSION),
            summary_updated_at=float(payload.get("summary_updated_at") or 0),
            summary_entities=[MemoryEntity(**entity) for entity in payload.get("summary_entities") or []],
            total_turns=int(payload.get("total_turns") or 0),
            compression_count=int(payload.get("compression_count") or 0),
            summary_fallback_used=bool(payload.get("summary_fallback_used")),
        )

    async def _save_persistent(self, key: str, record: SessionRecord) -> None:
        ttl = max(1, int(record.expires_at - self.clock()))
        await self.backend.set_json(self._storage_key(key), asdict(record), ttl)

    def _storage_key(self, key: str) -> str:
        return f"{settings.STATE_KEY_PREFIX}:session:{key}"

    async def _session_lock(self, key: str) -> asyncio.Lock:
        async with self._lock:
            self._cleanup_locked()
            return self._session_locks.setdefault(key, asyncio.Lock())

    def _key(
        self,
        session_id: str,
        auth_token: str | None,
        tenant_id: str = "default",
        user_id: int | None = None,
    ) -> str:
        safe_session_id = self._trim(session_id.strip(), 160)
        raw = f"{SESSION_RECORD_SCHEMA_VERSION}:{self._owner(auth_token)}:{tenant_id}:{user_id}:{safe_session_id}"
        return hashlib.sha256(raw.encode("utf-8")).hexdigest()

    def _owner(self, auth_token: str | None) -> str:
        return hashlib.sha256((auth_token or "guest").encode("utf-8")).hexdigest()[:24]

    def _matches_context(
        self,
        record: SessionRecord,
        session_id: str,
        auth_token: str | None,
        tenant_id: str,
        user_id: int | None,
    ) -> bool:
        return (
            record.schema_version == SESSION_RECORD_SCHEMA_VERSION
            and record.owner_fingerprint == self._owner(auth_token)
            and record.tenant_id == tenant_id
            and record.user_id == user_id
            and record.session_id == session_id
        )

    def _cleanup_locked(self) -> None:
        now = self.clock()
        expired = [key for key, record in self._sessions.items() if record.expires_at <= now]
        for key in expired:
            self._sessions.pop(key, None)
            self._session_locks.pop(key, None)

    def _empty_context(self, session_id: str) -> dict[str, Any]:
        return {
            "sessionId": session_id,
            "turnCount": 0,
            "recentTurnCount": 0,
            "totalTurnCount": 0,
            "recentTurns": [],
            "entities": [],
            "summary": "",
            "summaryVersion": SESSION_SUMMARY_VERSION,
            "summaryUpdatedAt": None,
            "compressionCount": 0,
            "estimatedTokens": 0,
            "truncated": False,
            "summaryFallbackUsed": False,
        }

    def _context(self, session_id: str, record: SessionRecord) -> dict[str, Any]:
        budget = max(128, self.context_token_budget)
        summary = self._trim(record.summary, settings.SESSION_MEMORY_SUMMARY_MAX_CHARS)
        truncated = summary != record.summary
        while summary and self._estimate_tokens(json.dumps([summary], ensure_ascii=False)) > budget:
            summary = summary[: max(1, len(summary) * 3 // 4)]
            truncated = True

        selected_turns: list[MemoryTurn] = []
        payload_parts: list[Any] = [summary] if summary else []
        for turn in reversed(record.turns):
            turn_payload = self._turn_dict(turn)
            if self._estimate_tokens(json.dumps(payload_parts + [turn_payload], ensure_ascii=False)) > budget:
                truncated = True
                continue
            selected_turns.append(turn)
            payload_parts.append(turn_payload)
        selected_turns.reverse()

        recent_entities = self._recent_entities(record.turns)
        entities = self._merge_entities(recent_entities, record.summary_entities, settings.SESSION_MEMORY_MAX_CONTEXT_ENTITIES)
        selected_entities: list[MemoryEntity] = []
        for entity in entities:
            entity_payload = asdict(entity)
            if self._estimate_tokens(json.dumps(payload_parts + [entity_payload], ensure_ascii=False)) > budget:
                truncated = True
                continue
            selected_entities.append(entity)
            payload_parts.append(entity_payload)

        estimated_tokens = self._estimate_tokens(json.dumps(payload_parts, ensure_ascii=False))
        return {
            "sessionId": session_id,
            "turnCount": len(record.turns),
            "recentTurnCount": len(selected_turns),
            "totalTurnCount": record.total_turns,
            "recentTurns": [
                self._turn_dict(turn)
                for turn in selected_turns
            ],
            "entities": [asdict(entity) for entity in selected_entities],
            "summary": summary,
            "summaryVersion": record.summary_version,
            "summaryUpdatedAt": record.summary_updated_at or None,
            "compressionCount": record.compression_count,
            "estimatedTokens": estimated_tokens,
            "truncated": truncated,
            "summaryFallbackUsed": record.summary_fallback_used,
        }

    def _turn_dict(self, turn: MemoryTurn) -> dict[str, Any]:
        return {
            "user": turn.user_message,
            "assistant": turn.assistant_answer,
            "intent": turn.intent,
            "toolSummaries": turn.tool_summaries,
            "traceId": turn.trace_id,
        }

    def _recent_entities(self, turns: list[MemoryTurn]) -> list[MemoryEntity]:
        result: list[MemoryEntity] = []
        seen: set[tuple[str, str]] = set()
        for turn in reversed(turns):
            for entity in turn.entities:
                key = (entity.kind, entity.entity_id)
                if key in seen:
                    continue
                seen.add(key)
                result.append(entity)
        return result[: settings.SESSION_MEMORY_MAX_CONTEXT_ENTITIES]

    def _merge_entities(
        self,
        primary: list[MemoryEntity],
        secondary: list[MemoryEntity],
        limit: int,
    ) -> list[MemoryEntity]:
        result: list[MemoryEntity] = []
        seen: set[tuple[str, str]] = set()
        for entity in [*primary, *secondary]:
            key = (entity.kind, entity.entity_id)
            if key in seen:
                continue
            seen.add(key)
            result.append(entity)
            if len(result) >= limit:
                break
        return result

    def _estimate_tokens(self, value: str) -> int:
        text = value or ""
        cjk = len(re.findall(r"[\u3400-\u9fff]", text))
        words = len(re.findall(r"[A-Za-z0-9_]+", text))
        punctuation = len(re.findall(r"[^\s\u3400-\u9fffA-Za-z0-9_]", text))
        return cjk + words + max(0, punctuation // 2)

    def _tool_summaries(self, tool_calls: list[ToolCallRecord]) -> list[str]:
        summaries: list[str] = []
        for record in tool_calls:
            status = "成功" if record.ok else "失败"
            detail = ""
            result = record.result
            if isinstance(result, list):
                detail = f"，返回 {len(result)} 条"
            elif isinstance(result, dict):
                detail_value = result.get("statusText") or result.get("name") or result.get("orderSn")
                if detail_value:
                    detail = f"，{detail_value}"
            summaries.append(f"{record.name}：{status}{detail}")
        return summaries[:8]

    def _extract_entities(self, tool_calls: list[ToolCallRecord]) -> list[MemoryEntity]:
        entities: list[MemoryEntity] = []
        for record in tool_calls:
            if not record.ok:
                continue
            if record.name in ("search_products", "get_product_detail", "compare_products"):
                products = self._product_results(record.result)
                for index, product in enumerate(products, start=1):
                    product_id = product.get("productId") or product.get("id")
                    if product_id is None:
                        continue
                    entities.append(
                        MemoryEntity(
                            kind="product",
                            entity_id=str(product_id),
                            label=str(product.get("name") or product.get("productName") or f"商品 {product_id}"),
                            status=str(product.get("stock") if product.get("stock") is not None else ""),
                            source_tool=record.name,
                            ordinal=index,
                        )
                    )
            elif record.name in ("list_my_orders", "get_my_order_detail", "get_order_detail"):
                for index, order in enumerate(self._dict_results(record.result), start=1):
                    order_ref = order.get("orderSn") or order.get("orderNo") or order.get("orderId") or order.get("id")
                    if order_ref is None:
                        continue
                    entities.append(
                        MemoryEntity(
                            kind="order",
                            entity_id=str(order_ref),
                            label=str(order.get("orderSn") or order.get("orderNo") or f"订单 {order_ref}"),
                            status=str(order.get("statusText") or order.get("status") or ""),
                            source_tool=record.name,
                            ordinal=index,
                        )
                    )
            elif record.name in ("list_my_returns", "get_return_detail"):
                for index, item in enumerate(self._dict_results(record.result), start=1):
                    return_id = item.get("returnId") or item.get("id")
                    if return_id is None:
                        continue
                    entities.append(
                        MemoryEntity(
                            kind="return",
                            entity_id=str(return_id),
                            label=str(item.get("typeText") or item.get("type") or f"售后单 {return_id}"),
                            status=str(item.get("statusText") or item.get("status") or ""),
                            source_tool=record.name,
                            ordinal=index,
                        )
                    )
        return entities

    def _product_results(self, result: Any) -> list[dict[str, Any]]:
        if isinstance(result, list):
            return [item for item in result if isinstance(item, dict)]
        if isinstance(result, dict) and isinstance(result.get("products"), list):
            return [item for item in result["products"] if isinstance(item, dict)]
        return [result] if isinstance(result, dict) else []

    def _dict_results(self, result: Any) -> list[dict[str, Any]]:
        if isinstance(result, list):
            return [item for item in result if isinstance(item, dict)]
        return [result] if isinstance(result, dict) else []

    def _trim(self, value: str, limit: int) -> str:
        text = re.sub(r"\s+", " ", value or "").strip()
        return text[:limit]


session_memory_store = SessionMemoryStore()
