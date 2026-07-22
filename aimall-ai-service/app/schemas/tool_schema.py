from typing import Any, Literal

from pydantic import BaseModel, Field


ToolRisk = Literal["LOW", "MEDIUM", "HIGH"]


class ToolDefinition(BaseModel):
    name: str
    description: str
    parameters: dict[str, Any] = Field(default_factory=dict)
    risk: ToolRisk = "LOW"
    requiresAuth: bool = False
    requiresConfirmation: bool = False
    timeoutSeconds: int = 8


class ToolCallRecord(BaseModel):
    name: str
    arguments: dict[str, Any] = Field(default_factory=dict)
    ok: bool
    result: Any = None
    error: str | None = None
    latencyMs: int = 0
    traceId: str
    guardrail: dict[str, Any] | None = None
