from __future__ import annotations

import asyncio
import re
from typing import Any

from app.config.settings import settings
from app.llm.agnes_client import agnes_client
from app.llm.chat_invoker import invoke_chat
from app.llm.model_router import ModelPurpose


SESSION_SUMMARY_VERSION = "SESSION_SUMMARY_V1"
SESSION_SUMMARY_PROMPT = """你是 AIMall 会话记忆压缩器。请将旧摘要和移出滑动窗口的对话合并为简洁中文摘要。
必须保留：用户目标、预算、用途、偏好、约束、已选择的商品/订单/售后、工具确认的事实、未解决问题和不确定性。
禁止输出：隐藏思维过程、登录凭据、完整电话或地址、原文没有支持的推断。只输出摘要正文。"""


class SessionSummarizer:
    async def summarize(self, previous_summary: str, turns: list[dict[str, Any]]) -> tuple[str, bool]:
        fallback = self.deterministic_summary(previous_summary, turns)
        if not agnes_client.enabled:
            return fallback, True
        context = {
            "previousSummary": self._redact(previous_summary),
            "evictedTurns": [self._safe_turn(turn) for turn in turns],
            "maxChars": settings.SESSION_MEMORY_SUMMARY_MAX_CHARS,
        }
        try:
            answer = await asyncio.wait_for(
                invoke_chat("更新会话摘要", SESSION_SUMMARY_PROMPT, context, purpose=ModelPurpose.SUMMARY),
                timeout=settings.SESSION_SUMMARY_TIMEOUT,
            )
            cleaned = self._trim(self._redact(answer), settings.SESSION_MEMORY_SUMMARY_MAX_CHARS)
            if cleaned:
                return cleaned, False
        except Exception:
            pass
        return fallback, True

    def deterministic_summary(self, previous_summary: str, turns: list[dict[str, Any]]) -> str:
        parts: list[str] = []
        previous = self._trim(self._redact(previous_summary), settings.SESSION_MEMORY_SUMMARY_MAX_CHARS)
        if previous:
            parts.append(previous)
        for turn in turns:
            safe = self._safe_turn(turn)
            segment = f"用户：{safe['user']}；助手：{safe['assistant']}"
            if safe["intent"]:
                segment += f"；意图：{safe['intent']}"
            if safe["toolSummaries"]:
                segment += f"；工具：{'；'.join(safe['toolSummaries'])}"
            parts.append(segment)
        return self._trim("\n".join(parts), settings.SESSION_MEMORY_SUMMARY_MAX_CHARS)

    def _safe_turn(self, turn: dict[str, Any]) -> dict[str, Any]:
        return {
            "user": self._trim(self._redact(str(turn.get("user") or "")), 500),
            "assistant": self._trim(self._redact(str(turn.get("assistant") or "")), 800),
            "intent": self._trim(str(turn.get("intent") or ""), 64),
            "toolSummaries": [self._trim(self._redact(str(item)), 160) for item in (turn.get("toolSummaries") or [])[:8]],
        }

    def _redact(self, value: str) -> str:
        text = value or ""
        text = re.sub(r"(?i)bearer\s+[a-z0-9._-]+", "Bearer [REDACTED]", text)
        text = re.sub(r"\bsk-[A-Za-z0-9_-]{12,}\b", "[REDACTED_KEY]", text)
        return re.sub(r"(?<!\d)1[3-9]\d{9}(?!\d)", "[REDACTED_PHONE]", text)

    @staticmethod
    def _trim(value: str, limit: int) -> str:
        return re.sub(r"[ \t]+", " ", value or "").strip()[:limit]


session_summarizer = SessionSummarizer()
