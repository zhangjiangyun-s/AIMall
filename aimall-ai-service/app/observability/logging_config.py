from __future__ import annotations

import json
import logging
import sys
import re
from datetime import datetime, timezone

from app.guardrails import guardrail_service
from app.observability.trace_context import current_client_trace_id, current_trace_id


class JsonLogFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        record_payload = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "level": record.levelname,
            "service": "aimall-ai-service",
            "logger": record.name,
            "traceId": current_trace_id(),
            "message": _sanitize_message(record.getMessage()),
        }
        client_trace_id = current_client_trace_id()
        if client_trace_id:
            record_payload["clientTraceId"] = client_trace_id
        payload = guardrail_service.sanitize_payload(record_payload)
        if record.exc_info:
            payload["exceptionType"] = record.exc_info[0].__name__
        return json.dumps(payload, ensure_ascii=False, default=str)


def configure_logging() -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonLogFormatter())
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(logging.INFO)


def _sanitize_message(message: str) -> str:
    value = re.sub(
        r"(?i)(password|token|authorization|secret|phone|address|raw[_-]?payload)\s*[=:]\s*([^,\s}]+)",
        r"\1=***",
        message or "",
    )
    value = re.sub(r"(?i)Bearer\s+[A-Za-z0-9._~+/-]+={0,2}", "Bearer ***", value)
    return re.sub(r"(?<!\d)1\d{10}(?!\d)", "***PHONE***", value)
