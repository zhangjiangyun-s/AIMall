from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from app.agent.react_agent import ReActAgent, react_agent
from app.multi_agent.contracts import DelegationPlan, DelegationStatus
from app.multi_agent.specialists import build_specialists
from app.multi_agent.supervisor import MultiAgentSupervisor, multi_agent_supervisor
from app.router.intent_router import detect_intent
from app.schemas.chat_schema import ChatRequest
from app.schemas.tool_schema import ToolCallRecord


MAX_MULTI_AGENT_TOOL_CALLS = 6


@dataclass(frozen=True)
class MultiAgentRun:
    intent: str
    tool_calls: list[ToolCallRecord]
    steps: list[dict[str, Any]]
    supervisor_plan: DelegationPlan
    used_legacy_fallback: bool


class MultiAgentOrchestrator:
    def __init__(self, react: ReActAgent, supervisor: MultiAgentSupervisor) -> None:
        self._react = react
        self._memory_resolver = ReActAgent()
        self._supervisor = supervisor
        self._specialists = build_specialists(react)

    async def run_tools(
        self,
        request: ChatRequest,
        trace_id: str,
        memory_context: dict[str, Any] | None = None,
    ) -> MultiAgentRun:
        page_type = request.pageContext.pageType if request.pageContext else None
        initial_intent = detect_intent(request.message, page_type)
        memory_context = memory_context or {}
        memory_entity = self._memory_resolver._resolve_memory_entity(request.message, memory_context)
        if memory_entity and initial_intent in {"GENERAL_QA", "POLICY_QA", "RECOMMENDATION"}:
            initial_intent = {
                "product": "PRODUCT_QA",
                "order": "ORDER_QA",
                "return": "RETURN_QA",
            }.get(str(memory_entity.get("kind")), initial_intent)
        plan = self._supervisor.plan(intent=initial_intent, message=request.message, page_context=request.pageContext)
        if plan.fallbackToLegacy:
            intent, tool_calls, steps = await self._react.run_tools(request, trace_id, memory_context)
            return MultiAgentRun(intent, tool_calls, steps, plan, True)

        all_calls: list[ToolCallRecord] = []
        all_steps: list[dict[str, Any]] = []
        executed_call_keys: set[str] = set()
        intent = initial_intent
        for delegation in plan.delegations:
            remaining_budget = MAX_MULTI_AGENT_TOOL_CALLS - len(all_calls)
            if remaining_budget <= 0:
                self._skip_remaining(plan, delegation.sequence)
                break
            specialist = self._specialists[delegation.specialist]
            try:
                result = await specialist.run(
                    request,
                    trace_id,
                    memory_context,
                    delegation,
                    excluded_tool_call_keys=executed_call_keys,
                    max_tool_calls=remaining_budget,
                )
            except Exception:
                delegation.status = DelegationStatus.FAILED
                self._skip_remaining(plan, delegation.sequence + 1)
                all_steps.append(
                    {
                        "agent": delegation.specialist.value,
                        "delegationSequence": delegation.sequence,
                        "thought": "专业 Agent 暂时不可用，已停止该领域的后续调用。",
                        "action": "final",
                        "arguments": {},
                        "candidateTools": delegation.allowedTools,
                    }
                )
                break

            delegation.status = result.status
            delegation.duplicateCallsPrevented = result.duplicate_calls_prevented
            all_calls.extend(result.tool_calls)
            executed_call_keys.update(
                ReActAgent.tool_call_key(call.name, call.arguments) for call in result.tool_calls
            )
            all_steps.extend(
                {
                    **step,
                    "agent": delegation.specialist.value,
                    "delegationSequence": delegation.sequence,
                }
                for step in result.steps
            )
            if result.status == DelegationStatus.FAILED:
                self._skip_remaining(plan, delegation.sequence + 1)
                break

        return MultiAgentRun(intent, self._dedupe_calls(all_calls), all_steps, plan, False)

    @staticmethod
    def _skip_remaining(plan: DelegationPlan, from_sequence: int) -> None:
        for item in plan.delegations:
            if item.sequence >= from_sequence and item.status == DelegationStatus.PLANNED:
                item.status = DelegationStatus.SKIPPED

    @staticmethod
    def _dedupe_calls(calls: list[ToolCallRecord]) -> list[ToolCallRecord]:
        result: list[ToolCallRecord] = []
        seen: set[tuple[str, str]] = set()
        for call in calls:
            key = (call.name, ReActAgent.tool_call_key(call.name, call.arguments))
            if key in seen:
                continue
            seen.add(key)
            result.append(call)
        return result


multi_agent_orchestrator = MultiAgentOrchestrator(react_agent, multi_agent_supervisor)
