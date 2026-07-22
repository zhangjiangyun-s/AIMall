import asyncio
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from app.config.settings import settings
from app.guardrails import guardrail_service


class AgentTraceLogger:
    def __init__(self, log_dir: str | None = None, retention_days: int | None = None) -> None:
        self.base_dir = self._resolve_log_dir(log_dir or settings.TRACE_LOG_DIR)
        self.retention_days = max(1, retention_days or settings.LOG_RETENTION_DAYS)
        self._lock = asyncio.Lock()

    def _resolve_log_dir(self, log_dir: str) -> Path:
        path = Path(log_dir)
        if not path.is_absolute():
            path = Path(__file__).resolve().parents[3] / path
        return path

    async def write(self, trace: dict[str, Any]) -> None:
        payload = guardrail_service.sanitize_payload({
            "loggedAt": datetime.now(timezone.utc).isoformat(),
            **trace,
        })
        line = json.dumps(payload, ensure_ascii=False, default=str)

        async with self._lock:
            self.base_dir.mkdir(parents=True, exist_ok=True)
            await asyncio.to_thread(self._prune_expired)
            file_path = self.base_dir / f"{datetime.now().strftime('%Y-%m-%d')}.jsonl"
            await asyncio.to_thread(self._append_line, file_path, line)

    def _append_line(self, file_path: Path, line: str) -> None:
        with file_path.open("a", encoding="utf-8") as file:
            file.write(line + "\n")

    def _prune_expired(self) -> None:
        cutoff = datetime.now(timezone.utc) - timedelta(days=self.retention_days)
        for file_path in self.base_dir.glob("*.jsonl"):
            try:
                modified = datetime.fromtimestamp(file_path.stat().st_mtime, timezone.utc)
                if modified < cutoff:
                    file_path.unlink()
            except OSError:
                continue

    async def owns_trace(self, trace_id: str, user_id: int | None, session_id: str | None) -> bool:
        if not trace_id or user_id is None or not session_id:
            return False
        return await asyncio.to_thread(self._owns_trace_sync, trace_id, user_id, session_id)

    def _owns_trace_sync(self, trace_id: str, user_id: int, session_id: str) -> bool:
        if not self.base_dir.is_dir():
            return False
        files = sorted(self.base_dir.glob("*.jsonl"), reverse=True)[:7]
        for file_path in files:
            try:
                with file_path.open("r", encoding="utf-8") as stream:
                    for line in stream:
                        if trace_id not in line:
                            continue
                        payload = json.loads(line)
                        if str(payload.get("traceId")) != trace_id:
                            continue
                        return (
                            str(payload.get("userId")) == str(user_id)
                            and str(payload.get("sessionId")) == session_id
                        )
            except (OSError, ValueError, json.JSONDecodeError):
                continue
        return False


agent_trace_logger = AgentTraceLogger(retention_days=settings.AUDIT_RETENTION_DAYS)
rag_feedback_logger = AgentTraceLogger(settings.FEEDBACK_LOG_DIR, settings.AUDIT_RETENTION_DAYS)
metrics_snapshot_logger = AgentTraceLogger(settings.METRICS_LOG_DIR, settings.LOG_RETENTION_DAYS)
