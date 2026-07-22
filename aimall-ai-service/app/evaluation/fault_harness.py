from __future__ import annotations

from typing import Any

from app.evaluation.models import EvaluationCase
from app.reflection import reflection_orchestrator, reflection_validator
from app.schemas.tool_schema import ToolCallRecord


class IsolatedFaultInjectionHarness:
    async def execute(self, case: EvaluationCase, run_id: str) -> dict[str, Any]:
        fault = case.fixture.faultInjections[0]
        trace_id = f"{run_id}-{case.id}-ISOLATED"
        session_id = f"eval-{run_id.lower()}-{case.id.lower()}"
        intent = case.expected.intentAnyOf[0] if case.expected.intentAnyOf else "GENERAL_QA"
        attempts = 2 if fault.behavior == "TIMEOUT" else 1
        error = self._error_message(fault.behavior, fault.errorCode)
        tool_call = ToolCallRecord(
            name=fault.toolName,
            arguments={},
            ok=False,
            error=error,
            traceId=trace_id,
        )
        retrieval_status = (
            "ERROR"
            if fault.toolName == "search_policy_kb" and fault.behavior == "ERROR"
            else "TIMEOUT"
            if fault.toolName == "search_policy_kb"
            else None
        )
        evidence_payload = {
            "policySearched": fault.toolName == "search_policy_kb",
            "retrievalStatus": retrieval_status,
            "citations": [],
            "evidence": [],
            "businessEvidence": [],
        }
        decision = reflection_validator.evaluate(
            query=case.query,
            intent=intent,
            answer="",
            trace_id=trace_id,
            evidence_payload=evidence_payload,
            tool_calls=[tool_call],
            page_context=case.fixture.pageContext,
            attempt=1,
            max_attempts=1,
            check_answer=False,
        )
        terminal = reflection_orchestrator.terminal_from_prerequisite(decision, evidence_payload)
        reflection = {
            "status": decision.status.value,
            "action": decision.action.value,
            "terminal": decision.terminal,
            "generationAttempts": 0,
            "prerequisiteRetried": attempts > 1,
        }
        refusal_reason = fault.errorCode or f"TOOL_{fault.behavior}"
        public_tool = {
            "name": tool_call.name,
            "arguments": {},
            "ok": False,
            "error": refusal_reason,
            "latencyMs": 0,
        }
        done = {
            "type": "done",
            "intent": intent,
            "agentMode": "ISOLATED_FAULT_HARNESS_V1",
            "toolCalls": [public_tool],
            "businessEvidence": [],
            "ragCitations": [],
            "ragEvidence": [],
            "retrievalStatus": retrieval_status,
            "refusalReason": refusal_reason,
            "reflection": reflection,
            "ragValidation": reflection,
            "pendingActions": [],
            "guardrails": [],
            "traceId": trace_id,
            "faultInjection": {
                "isolated": True,
                "toolName": fault.toolName,
                "behavior": fault.behavior,
                "attemptCount": attempts,
                "errorCode": fault.errorCode,
            },
        }
        events = [
            {"type": "tool_call", "toolName": fault.toolName, "arguments": {}, "traceId": trace_id},
            {
                "type": "tool_result",
                "toolName": fault.toolName,
                "ok": False,
                "error": refusal_reason,
                "traceId": trace_id,
            },
            {"type": "reflection", **reflection, "traceId": trace_id},
            {"type": "delta", "content": terminal.answer, "traceId": trace_id},
            done,
        ]
        return {
            "caseId": case.id,
            "category": case.category.value,
            "riskLevel": case.riskLevel.value,
            "sessionId": session_id,
            "traceId": trace_id,
            "status": "SUCCESS",
            "durationMs": 0,
            "httpStatus": None,
            "rawBytes": 0,
            "rawSha256": None,
            "historyRuns": [],
            "answer": terminal.answer,
            "done": done,
            "events": events,
            "errorType": None,
            "errorMessage": None,
            "executionMode": "ISOLATED_FAULT_HARNESS_V1",
        }

    def _error_message(self, behavior: str, error_code: str | None) -> str:
        if behavior == "TIMEOUT":
            return f"connection timeout ({error_code or 'EVAL_TIMEOUT'})"
        if behavior == "EMPTY_RESULT":
            return f"empty result ({error_code or 'EVAL_EMPTY'})"
        return f"tool error ({error_code or 'EVAL_ERROR'})"


isolated_fault_injection_harness = IsolatedFaultInjectionHarness()
