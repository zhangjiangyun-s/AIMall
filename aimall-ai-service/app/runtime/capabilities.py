from __future__ import annotations

import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from app.config.settings import settings


CAPABILITY_MATRIX: dict[str, dict[str, Any]] = {
    "MOCK": {
        "llm": False,
        "rag": False,
        "readTools": "SIMULATED",
        "writeTools": "DISABLED",
        "payment": "DISABLED",
    },
    "RULE_BASED": {
        "llm": False,
        "rag": "OPTIONAL",
        "readTools": "REAL_READ_ONLY",
        "writeTools": "DISABLED",
        "payment": "DISABLED",
    },
    "LLM": {
        "llm": True,
        "rag": "OPTIONAL",
        "readTools": "REAL_READ_ONLY",
        "writeTools": "CONFIRMATION_REQUIRED",
        "payment": "DISABLED",
    },
    "SANDBOX": {
        "llm": True,
        "rag": True,
        "readTools": "REAL_READ_ONLY",
        "writeTools": "SANDBOX",
        "payment": "SANDBOX",
    },
    "PRODUCTION": {
        "llm": True,
        "rag": True,
        "readTools": "REAL_READ_ONLY",
        "writeTools": "REAL_CONFIRMATION_PERMISSION",
        "payment": "REAL_PROVIDER",
    },
}


class RuntimeCapabilityError(RuntimeError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


class RuntimeCapabilities:
    @property
    def mode(self) -> str:
        return settings.AI_RUNTIME_MODE

    def values(self) -> dict[str, Any]:
        values = dict(CAPABILITY_MATRIX[self.mode])
        if values["rag"] == "OPTIONAL":
            values["rag"] = bool(settings.RAG_ENABLED)
        return values

    def capability_hash(self) -> str:
        payload = {"mode": self.mode, "capabilities": self.values()}
        encoded = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
        return hashlib.sha256(encoded).hexdigest()

    def snapshot(self, degraded_reasons: list[str] | None = None) -> dict[str, Any]:
        reasons = list(dict.fromkeys(degraded_reasons or []))
        return {
            "mode": self.mode,
            "capabilities": self.values(),
            "capabilityHash": self.capability_hash(),
            "degradedReason": reasons[0] if reasons else None,
            "degradedReasons": reasons,
            "featureFlag": {
                "targetMode": settings.AI_RUNTIME_MODE_TARGET,
                "previousMode": settings.AI_RUNTIME_MODE_PREVIOUS,
                "rolloutPercent": settings.AI_RUNTIME_MODE_ROLLOUT_PERCENT,
                "changeId": settings.AI_RUNTIME_MODE_CHANGE_ID,
            },
        }

    def assert_write_allowed(self) -> None:
        mode = self.values()["writeTools"]
        if mode == "DISABLED":
            raise RuntimeCapabilityError("AI_WRITE_DISABLED", f"Write tools are disabled in {self.mode} mode")

    def simulated_read_result(self, tool_name: str, arguments: dict[str, Any]) -> dict[str, Any] | None:
        if self.values()["readTools"] != "SIMULATED":
            return None
        return {"simulated": True, "tool": tool_name, "arguments": arguments, "items": []}

    def audit_startup(self) -> dict[str, Any]:
        payload = {
            "event": "AI_RUNTIME_MODE_RESOLVED",
            "occurredAt": datetime.now(timezone.utc).isoformat(),
            "instanceKeyHash": hashlib.sha256(settings.AI_RUNTIME_INSTANCE_KEY.encode("utf-8")).hexdigest()[:16],
            **self.snapshot(),
        }
        path = Path(settings.RUNTIME_MODE_AUDIT_LOG)
        path.parent.mkdir(parents=True, exist_ok=True)
        line = json.dumps(payload, ensure_ascii=True, sort_keys=True)
        with path.open("a", encoding="utf-8") as stream:
            stream.write(line + "\n")
            stream.flush()
            os.fsync(stream.fileno())
        return payload


runtime_capabilities = RuntimeCapabilities()
