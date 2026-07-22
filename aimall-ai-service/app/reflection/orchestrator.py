from __future__ import annotations

from copy import deepcopy
import time
from typing import Any

from pydantic import BaseModel, Field

from app.guardrails import StreamingRedactor
from app.llm.agnes_client import agnes_client
from app.llm.chat_invoker import invoke_chat
from app.llm.model_router import ModelPurpose
from app.reflection.models import ReflectionAction, ReflectionDecision, ReflectionRequest
from app.reflection.semantic_judge import SemanticJudgeResult, SemanticJudgeStatus, semantic_judge
from app.reflection.service import reflection_service
from app.reflection.validator import reflection_validator
from app.schemas.tool_schema import ToolCallRecord


class ReflectedGenerationResult(BaseModel):
    answer: str
    decision: ReflectionDecision
    generationAttempts: int = Field(ge=0, le=2)
    modelDegraded: bool = False
    judgeDegraded: bool = False
    semanticJudge: dict[str, Any] = Field(default_factory=dict)
    pipelineLatencyMs: int = Field(default=0, ge=0)
    outputGuardrails: list[dict[str, object]] = Field(default_factory=list)


class ReflectionOrchestrator:
    async def generate(
        self,
        *,
        query: str,
        intent: str,
        trace_id: str,
        system_prompt: str,
        context: dict[str, Any],
        evidence_payload: dict[str, Any] | None,
        tool_calls: list[ToolCallRecord],
        page_context: dict[str, Any] | None,
        fallback_answer: str,
        max_attempts: int = 1,
    ) -> ReflectedGenerationResult:
        started_at = time.perf_counter()
        output_guardrails: list[dict[str, object]] = []
        model_degraded = False
        candidate = ""
        decision: ReflectionDecision | None = None
        judge_result = SemanticJudgeResult(
            status=SemanticJudgeStatus.SKIPPED,
            attempted=False,
            reason="DETERMINISTIC_NOT_PASSED",
        )
        attempts_used = 0

        for attempt in range(max_attempts + 1):
            generation_context = self._revision_context(context, candidate, decision) if attempt else context
            attempts_used = attempt + 1
            try:
                raw_candidate = await invoke_chat(query, system_prompt, generation_context, purpose=ModelPurpose.GENERATION)
            except Exception:
                raw_candidate = fallback_answer
                model_degraded = True
            candidate, output_summary = self._sanitize(raw_candidate)
            if output_summary:
                output_guardrails.append(output_summary)
            decision = reflection_validator.evaluate(
                query=query,
                intent=intent,
                answer=candidate,
                trace_id=trace_id,
                evidence_payload=evidence_payload,
                tool_calls=tool_calls,
                page_context=page_context,
                attempt=attempt,
                max_attempts=max_attempts,
            )
            if decision.passed:
                judge_result = await semantic_judge.evaluate(
                    query=query,
                    intent=intent,
                    answer=candidate,
                    evidence_payload=evidence_payload,
                )
                if judge_result.status != SemanticJudgeStatus.FAILED:
                    return ReflectedGenerationResult(
                        answer=candidate,
                        decision=decision,
                        generationAttempts=attempts_used,
                        modelDegraded=model_degraded,
                        judgeDegraded=judge_result.status == SemanticJudgeStatus.DEGRADED,
                        semanticJudge=judge_result.model_dump(mode="json"),
                        pipelineLatencyMs=self._latency_ms(started_at),
                        outputGuardrails=output_guardrails,
                    )
                decision = reflection_service.decide(
                    ReflectionRequest(
                        query=query,
                        intent=intent,
                        answer=candidate,
                        traceId=trace_id,
                        attempt=attempt,
                        maxAttempts=max_attempts,
                        hasEvidence=bool((evidence_payload or {}).get("citations") or (evidence_payload or {}).get("evidence")),
                        hasBusinessEvidence=bool((evidence_payload or {}).get("businessEvidence")),
                    ),
                    judge_result.findings,
                )
            if decision.action != ReflectionAction.RETRY_GENERATION or model_degraded:
                if not decision.terminal:
                    decision = reflection_validator.evaluate(
                        query=query,
                        intent=intent,
                        answer=candidate,
                        trace_id=trace_id,
                        evidence_payload=evidence_payload,
                        tool_calls=tool_calls,
                        page_context=page_context,
                        attempt=max_attempts,
                        max_attempts=max_attempts,
                    )
                break

        if decision is None:
            raise RuntimeError("Reflection generation did not produce a decision")
        return ReflectedGenerationResult(
            answer=self._terminal_answer(decision, evidence_payload),
            decision=decision,
            generationAttempts=attempts_used,
            modelDegraded=model_degraded,
            judgeDegraded=judge_result.status == SemanticJudgeStatus.DEGRADED,
            semanticJudge=judge_result.model_dump(mode="json"),
            pipelineLatencyMs=self._latency_ms(started_at),
            outputGuardrails=output_guardrails,
        )

    def terminal_from_prerequisite(
        self,
        decision: ReflectionDecision,
        evidence_payload: dict[str, Any] | None,
    ) -> ReflectedGenerationResult:
        return ReflectedGenerationResult(
            answer=self._terminal_answer(decision, evidence_payload),
            decision=decision,
            generationAttempts=0,
            modelDegraded=True,
            semanticJudge=SemanticJudgeResult(
                status=SemanticJudgeStatus.SKIPPED,
                attempted=False,
                reason="PREREQUISITE_NOT_PASSED",
            ).model_dump(mode="json"),
            pipelineLatencyMs=0,
        )

    def _latency_ms(self, started_at: float) -> int:
        return max(0, int((time.perf_counter() - started_at) * 1000))

    def _sanitize(self, text: str) -> tuple[str, dict[str, object] | None]:
        redactor = StreamingRedactor()
        safe_text = redactor.feed(text) + redactor.finish()
        return safe_text, redactor.public_summary()

    def _revision_context(
        self,
        context: dict[str, Any],
        previous_answer: str,
        decision: ReflectionDecision | None,
    ) -> dict[str, Any]:
        revised = deepcopy(context)
        revised["reflectionRevision"] = {
            "previousAnswer": previous_answer,
            "issues": [
                {
                    "issueType": finding.issueType.value,
                    "message": finding.message,
                    "evidenceRefs": finding.evidenceRefs,
                    "metadata": finding.metadata,
                }
                for finding in (decision.findings if decision else [])
            ],
            "instruction": "只修正列出的问题，严格使用现有证据和引用编号，不添加新事实。",
        }
        return revised

    def _terminal_answer(
        self,
        decision: ReflectionDecision,
        evidence_payload: dict[str, Any] | None,
    ) -> str:
        if decision.action == ReflectionAction.REQUEST_CLARIFICATION:
            return "我还不能确定你具体想查询的商品、订单或售后申请，请补充对象或编号后再试。"
        if decision.action == ReflectionAction.HANDOFF_HUMAN:
            return "核对时发现业务数据或操作状态存在冲突。为避免给出错误结论，请以商城页面显示为准并联系人工客服确认。"
        if decision.action == ReflectionAction.RETURN_EVIDENCE_ONLY:
            citations = (evidence_payload or {}).get("citations") or []
            lines = ["自动总结未通过一致性校验，以下仅展示已核验的原始依据："]
            for item in citations[:3]:
                if isinstance(item, dict):
                    lines.append(f"[{item.get('id')}] {str(item.get('snippet') or '').strip()}")
            if len(lines) > 1:
                return "\n".join(lines)
            return "自动总结未通过一致性校验，请以当前商城业务页面和操作确认卡片显示为准。"
        return "由于缺少依据，或现有依据不够可靠且一致，我暂时不能给出结论。请补充信息或联系人工客服确认。"


reflection_orchestrator = ReflectionOrchestrator()
