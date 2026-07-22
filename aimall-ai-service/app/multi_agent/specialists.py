from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from app.agent.react_agent import ReActAgent
from app.multi_agent.contracts import Delegation, DelegationStatus, SpecialistId
from app.schemas.chat_schema import ChatRequest
from app.schemas.tool_schema import ToolCallRecord
from app.tools.tool_router import TOOL_GROUPS


SPECIALIST_TOOL_ALLOWLISTS: dict[SpecialistId, tuple[str, ...]] = {
    SpecialistId.PRODUCT: TOOL_GROUPS["product"],
    SpecialistId.ORDER: tuple(dict.fromkeys((
        *TOOL_GROUPS["order"],
        *TOOL_GROUPS["coupon"],
        *TOOL_GROUPS["return"],
        *TOOL_GROUPS["address"],
    ))),
    SpecialistId.POLICY: TOOL_GROUPS["policy"],
}


@dataclass(frozen=True)
class SpecialistRunResult:
    specialist: SpecialistId
    intent: str
    tool_calls: list[ToolCallRecord]
    steps: list[dict[str, Any]]
    status: DelegationStatus
    duplicate_calls_prevented: int = 0


class CapabilityScopedSpecialist:
    """Runs the existing ReAct loop with an immutable supervisor-approved tool allow-list."""

    def __init__(self, specialist: SpecialistId, react: ReActAgent) -> None:
        self.specialist = specialist
        self._react = react

    async def run(
        self,
        request: ChatRequest,
        trace_id: str,
        memory_context: dict[str, Any],
        delegation: Delegation,
        excluded_tool_call_keys: set[str] | None = None,
        max_tool_calls: int = 6,
    ) -> SpecialistRunResult:
        if delegation.specialist != self.specialist:
            raise ValueError("delegation specialist does not match executor")
        capabilities = set(SPECIALIST_TOOL_ALLOWLISTS[self.specialist])
        if not delegation.allowedTools or not set(delegation.allowedTools).issubset(capabilities):
            raise ValueError("delegation contains tools outside specialist capabilities")
        intent, tool_calls, steps = await self._react.run_tools(
            request,
            trace_id,
            memory_context,
            allowed_tool_names=list(delegation.allowedTools),
            strict_allowed_tools=True,
            intent_override="POLICY_QA" if self.specialist == SpecialistId.POLICY else None,
            excluded_tool_call_keys=excluded_tool_call_keys,
            max_tool_calls=max_tool_calls,
        )
        status = DelegationStatus.FAILED if any(not call.ok for call in tool_calls) else DelegationStatus.COMPLETED
        return SpecialistRunResult(
            specialist=self.specialist,
            intent=intent,
            tool_calls=tool_calls,
            steps=steps,
            status=status,
            duplicate_calls_prevented=sum(1 for step in steps if step.get("duplicateCallPrevented")),
        )


def build_specialists(react: ReActAgent) -> dict[SpecialistId, CapabilityScopedSpecialist]:
    return {specialist: CapabilityScopedSpecialist(specialist, react) for specialist in SpecialistId}


def specialist_allowed_tools(specialist: SpecialistId) -> tuple[str, ...]:
    return SPECIALIST_TOOL_ALLOWLISTS[specialist]
