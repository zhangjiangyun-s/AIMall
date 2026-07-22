import asyncio
import json

import pytest
from pydantic import ValidationError

from app.reflection import (
    ReflectionAction,
    ReflectionDecision,
    ReflectionFinding,
    ReflectionIssueType,
    ReflectionRequest,
    ReflectionSeverity,
    ReflectionStatus,
    SemanticJudgeResult,
    SemanticJudgeStatus,
    reflection_service,
    reflection_orchestrator,
    reflection_validator,
    semantic_judge,
)
from app.config.settings import settings
from app.llm.agnes_client import agnes_client
from app.api import chat_api
from app.schemas.chat_schema import ChatRequest
from app.schemas.tool_schema import ToolCallRecord


def request(**updates) -> ReflectionRequest:
    values = {
        "query": "退款需要多久？",
        "intent": "POLICY_QA",
        "answer": "退款将在审核后原路退回。[1]",
        "traceId": "trace-reflection",
        "attempt": 0,
        "maxAttempts": 1,
        "hasEvidence": True,
    }
    values.update(updates)
    return ReflectionRequest(**values)


def finding(issue_type, *, severity=ReflectionSeverity.HIGH, retryable=True) -> ReflectionFinding:
    return ReflectionFinding(
        issueType=issue_type,
        severity=severity,
        message="校验未通过",
        retryable=retryable,
    )


def test_no_findings_accepts_answer():
    decision = reflection_service.decide(request(), [])

    assert decision.passed is True
    assert decision.status == ReflectionStatus.PASSED
    assert decision.action == ReflectionAction.ACCEPT
    assert decision.terminal is True


def test_retrieval_failure_retries_before_budget_is_exhausted():
    decision = reflection_service.decide(
        request(),
        [finding(ReflectionIssueType.RETRIEVAL_FAILED)],
    )

    assert decision.status == ReflectionStatus.RETRY_REQUIRED
    assert decision.action == ReflectionAction.RETRY_RETRIEVAL
    assert decision.terminal is False


def test_missing_evidence_refuses_after_retry_budget_is_exhausted():
    decision = reflection_service.decide(
        request(attempt=1, hasEvidence=False),
        [finding(ReflectionIssueType.MISSING_EVIDENCE)],
    )

    assert decision.status == ReflectionStatus.REFUSED
    assert decision.action == ReflectionAction.REFUSE


def test_unsupported_fact_retries_generation():
    decision = reflection_service.decide(
        request(),
        [finding(ReflectionIssueType.UNSUPPORTED_FACT)],
    )

    assert decision.action == ReflectionAction.RETRY_GENERATION
    assert decision.terminal is False


def test_failed_generation_degrades_to_evidence_after_retry_budget():
    decision = reflection_service.decide(
        request(attempt=1),
        [finding(ReflectionIssueType.INVALID_CITATION)],
    )

    assert decision.status == ReflectionStatus.DEGRADED
    assert decision.action == ReflectionAction.RETURN_EVIDENCE_ONLY
    assert decision.terminal is True


def test_ambiguous_intent_requests_clarification_without_retrying():
    decision = reflection_service.decide(
        request(),
        [finding(ReflectionIssueType.USER_INTENT_AMBIGUOUS, severity=ReflectionSeverity.MEDIUM)],
    )

    assert decision.status == ReflectionStatus.CLARIFICATION_REQUIRED
    assert decision.action == ReflectionAction.REQUEST_CLARIFICATION
    assert decision.terminal is True


def test_business_conflict_requires_human_review():
    decision = reflection_service.decide(
        request(),
        [finding(ReflectionIssueType.BUSINESS_FACT_CONFLICT, retryable=False)],
    )

    assert decision.status == ReflectionStatus.HUMAN_REVIEW_REQUIRED
    assert decision.action == ReflectionAction.HANDOFF_HUMAN


def test_critical_issue_has_highest_priority():
    decision = reflection_service.decide(
        request(),
        [
            finding(ReflectionIssueType.USER_INTENT_AMBIGUOUS, severity=ReflectionSeverity.MEDIUM),
            finding(ReflectionIssueType.EVIDENCE_CONTRADICTION, severity=ReflectionSeverity.CRITICAL),
        ],
    )

    assert decision.action == ReflectionAction.HANDOFF_HUMAN


def test_decision_model_rejects_incoherent_state():
    with pytest.raises(ValidationError):
        ReflectionDecision(
            passed=True,
            status=ReflectionStatus.RETRY_REQUIRED,
            action=ReflectionAction.RETRY_GENERATION,
            terminal=False,
            attempt=0,
            maxAttempts=1,
            findings=[],
            policyVersion="REFLECTION_V1",
        )


def policy_payload(snippet="支持 7 天退货。"):
    return {
        "policySearched": True,
        "retrievalStatus": "OK",
        "citations": [{"id": "1", "snippet": snippet}],
        "evidence": [{"citationId": "1", "snippet": snippet}],
        "businessEvidence": [],
    }


def evaluate(answer, payload=None, intent="POLICY_QA", tool_calls=None, query="支持多久退货？"):
    if tool_calls is None:
        tool_calls = [
            ToolCallRecord(
                name="search_policy_kb",
                arguments={"keyword": "退货"},
                ok=True,
                result={},
                traceId="trace-deterministic-validator",
            )
        ] if intent == "POLICY_QA" else []
    return reflection_validator.evaluate(
        query=query,
        intent=intent,
        answer=answer,
        trace_id="trace-deterministic-validator",
        evidence_payload=policy_payload() if payload is None else payload,
        tool_calls=tool_calls,
    )


def test_valid_policy_answer_with_citation_passes():
    decision = evaluate("该商品支持 7 天退货。[1]")

    assert decision.passed is True
    assert decision.action == ReflectionAction.ACCEPT


def test_citation_marker_is_not_mistaken_for_unsupported_number():
    decision = evaluate("退货要求如下：\n1. 保持商品完好。[1]", policy_payload("商品需要保持完好。"))

    assert all(item.issueType != ReflectionIssueType.UNSUPPORTED_FACT for item in decision.findings)


def test_missing_policy_citation_requests_generation_retry():
    decision = evaluate("该商品支持 7 天退货。")

    assert decision.action == ReflectionAction.RETRY_GENERATION
    assert {item.issueType for item in decision.findings} == {ReflectionIssueType.MISSING_CITATION}


def test_invalid_policy_and_business_citation_ids_are_reported():
    payload = policy_payload()
    payload["businessEvidence"] = [{"id": "B1", "result": {"status": "待发货"}}]
    decision = evaluate("支持 7 天退货 [2]，订单状态为待发货 [B9]。", payload)

    invalid = next(item for item in decision.findings if item.issueType == ReflectionIssueType.INVALID_CITATION)
    assert invalid.metadata["invalidCitationIds"] == ["2", "B9"]


def test_unsupported_number_or_date_is_reported():
    decision = evaluate("该商品支持 15 天退货。[1]")

    unsupported = next(item for item in decision.findings if item.issueType == ReflectionIssueType.UNSUPPORTED_FACT)
    assert unsupported.metadata["unsupportedFacts"] == ["15天"]


def test_citation_must_locally_support_its_numeric_fact():
    payload = policy_payload("通用售后流程由商品类目和订单状态决定。")
    payload["citations"].append(
        {
            "id": "2",
            "title": "退货规则",
            "snippet": "符合条件的商品支持 7 天无理由退货。",
        }
    )
    decision = evaluate("商品支持 7 天无理由退货 [1]。", payload)

    local_finding = next(
        item
        for item in decision.findings
        if item.issueType == ReflectionIssueType.UNSUPPORTED_FACT
        and item.metadata.get("scope") == "CITATION_LOCAL"
    )
    assert local_finding.metadata["citationId"] == "1"
    assert local_finding.metadata["unsupportedFacts"] == ["7天"]


def test_missing_policy_evidence_requests_retrieval_retry():
    decision = evaluate(
        "暂时没有找到政策依据。",
        {
            "policySearched": True,
            "retrievalStatus": "NO_MATCH",
            "citations": [],
            "evidence": [],
            "businessEvidence": [],
        },
    )

    assert decision.action == ReflectionAction.RETRY_RETRIEVAL
    assert any(item.issueType == ReflectionIssueType.MISSING_EVIDENCE for item in decision.findings)


def test_business_only_answer_can_pass_with_valid_business_reference():
    order_tool = ToolCallRecord(
        name="get_my_order_detail",
        arguments={"orderSn": "AIM202607150001"},
        ok=True,
        result={"statusText": "待发货"},
        traceId="trace-business",
    )
    decision = evaluate(
        "订单当前为待发货 [B1]。",
        {
            "policySearched": False,
            "retrievalStatus": None,
            "citations": [],
            "evidence": [],
            "businessEvidence": [{"id": "B1", "result": {"statusText": "待发货"}}],
        },
        intent="ORDER_QA",
        tool_calls=[order_tool],
        query="我的订单是什么状态",
    )

    assert decision.passed is True


def test_missing_required_product_tool_retries_tool_execution():
    decision = evaluate(
        "这款商品适合日常使用。",
        {"policySearched": False, "citations": [], "evidence": [], "businessEvidence": []},
        intent="PRODUCT_QA",
        tool_calls=[],
        query="这款商品适合什么人",
    )

    assert decision.action == ReflectionAction.RETRY_TOOL_EXECUTION
    assert any(item.issueType == ReflectionIssueType.TASK_INCOMPLETE for item in decision.findings)


def test_failed_required_tool_is_recorded_without_fake_success():
    failed = ToolCallRecord(
        name="get_my_order_detail",
        arguments={"orderSn": "AIM202607150001"},
        ok=False,
        error="connection timeout",
        traceId="trace-tool-failure",
    )
    decision = evaluate(
        "暂时无法查询订单。",
        {"policySearched": False, "citations": [], "evidence": [], "businessEvidence": []},
        intent="ORDER_QA",
        tool_calls=[failed],
        query="订单 AIM202607150001 是什么状态",
    )

    issue_types = {item.issueType for item in decision.findings}
    assert ReflectionIssueType.TOOL_FAILURE in issue_types
    assert ReflectionIssueType.TASK_INCOMPLETE in issue_types
    assert decision.action == ReflectionAction.RETRY_TOOL_EXECUTION


def test_order_status_conflict_requires_human_review():
    order_tool = ToolCallRecord(
        name="get_my_order_detail",
        arguments={"orderSn": "AIM202607150001"},
        ok=True,
        result={"statusText": "待发货"},
        traceId="trace-status-conflict",
    )
    decision = evaluate(
        "订单已经完成 [B1]。",
        {"policySearched": False, "citations": [], "evidence": [], "businessEvidence": [{"id": "B1", "result": order_tool.result}]},
        intent="ORDER_QA",
        tool_calls=[order_tool],
        query="我的订单是什么状态",
    )

    assert decision.status == ReflectionStatus.HUMAN_REVIEW_REQUIRED
    assert any(item.issueType == ReflectionIssueType.BUSINESS_FACT_CONFLICT for item in decision.findings)


def test_product_price_and_stock_match_tool_result():
    product_tool = ToolCallRecord(
        name="get_product_detail",
        arguments={"productId": 1},
        ok=True,
        result={"name": "轻薄本", "price": 3999, "stock": 55},
        traceId="trace-product-facts",
    )
    decision = evaluate(
        "当前价格 3999 元，库存 55 件 [B1]。",
        {"policySearched": False, "citations": [], "evidence": [], "businessEvidence": [{"id": "B1", "result": product_tool.result}]},
        intent="PRODUCT_QA",
        tool_calls=[product_tool],
        query="这款商品多少钱还有库存吗",
    )

    assert decision.passed is True


def test_product_price_conflict_requires_human_review():
    product_tool = ToolCallRecord(
        name="get_product_detail",
        arguments={"productId": 1},
        ok=True,
        result={"price": 3999, "stock": 55},
        traceId="trace-product-price-conflict",
    )
    decision = evaluate(
        "当前价格 2999 元，库存 55 件 [B1]。",
        {"policySearched": False, "citations": [], "evidence": [], "businessEvidence": [{"id": "B1", "result": product_tool.result}]},
        intent="PRODUCT_QA",
        tool_calls=[product_tool],
        query="这款商品多少钱",
    )

    assert decision.action == ReflectionAction.HANDOFF_HUMAN


def test_money_fact_tokens_ignore_currency_symbol_and_trailing_zero_differences():
    product_tool = ToolCallRecord(
        name="get_product_detail",
        arguments={"productId": 1002},
        ok=True,
        result={"price": 3799.0, "originalPrice": 4299.0, "stock": 55},
        traceId="trace-product-money-normalization",
    )
    decision = evaluate(
        "这款商品现价 ¥3799.0，原价 ¥4299.00，库存 55 件 [B1]。",
        {
            "policySearched": False,
            "citations": [],
            "evidence": [],
            "businessEvidence": [{"id": "B1", "result": product_tool.result}],
        },
        intent="PRODUCT_QA",
        tool_calls=[product_tool],
        query="第一款商品多少钱",
    )

    assert decision.passed is True
    assert all(item.issueType != ReflectionIssueType.UNSUPPORTED_FACT for item in decision.findings)


def test_pending_action_must_not_be_claimed_as_executed():
    pending_tool = ToolCallRecord(
        name="cancel_order_confirmed",
        arguments={"orderSn": "AIM202607150001"},
        ok=True,
        result={"pendingAction": {"actionId": "pa-1", "status": "PENDING"}},
        traceId="trace-pending-conflict",
    )
    decision = evaluate(
        "已经成功取消订单。",
        {"policySearched": False, "citations": [], "evidence": [], "businessEvidence": [{"id": "B1", "result": pending_tool.result}]},
        intent="ORDER_QA",
        tool_calls=[pending_tool],
        query="取消订单 AIM202607150001",
    )

    assert decision.action == ReflectionAction.HANDOFF_HUMAN
    assert any(item.issueType == ReflectionIssueType.CONFIRMATION_STATE_ERROR for item in decision.findings)


def test_pending_action_confirmation_wording_is_allowed():
    pending_tool = ToolCallRecord(
        name="cancel_order_confirmed",
        arguments={"orderSn": "AIM202607150001"},
        ok=True,
        result={"pendingAction": {"actionId": "pa-1", "status": "PENDING"}},
        traceId="trace-pending-safe",
    )
    decision = evaluate(
        "已创建取消订单待确认操作，尚未执行 [B1]。",
        {"policySearched": False, "citations": [], "evidence": [], "businessEvidence": [{"id": "B1", "result": pending_tool.result}]},
        intent="ORDER_QA",
        tool_calls=[pending_tool],
        query="取消订单 AIM202607150001",
    )

    assert decision.passed is True


def test_mixed_order_policy_question_requires_business_and_policy_tools():
    order_tool = ToolCallRecord(
        name="get_my_order_detail",
        arguments={"orderSn": "AIM202607150001"},
        ok=True,
        result={"statusText": "待支付"},
        traceId="trace-mixed-missing-policy",
    )
    decision = evaluate(
        "订单待支付。",
        {"policySearched": False, "retrievalStatus": None, "citations": [], "evidence": [], "businessEvidence": [{"id": "B1", "result": order_tool.result}]},
        intent="ORDER_QA",
        tool_calls=[order_tool],
        query="订单 AIM202607150001 多久自动关闭",
    )

    missing_groups = [item.metadata.get("requiredAnyOf") for item in decision.findings if item.issueType == ReflectionIssueType.TASK_INCOMPLETE]
    assert ["search_policy_kb"] in missing_groups
    assert decision.action == ReflectionAction.RETRY_RETRIEVAL


def test_user_budget_is_an_allowed_fact_source():
    search_tool = ToolCallRecord(
        name="search_products",
        arguments={"keyword": "轻薄本", "maxPrice": 3000},
        ok=True,
        result=[],
        traceId="trace-user-budget",
    )
    decision = evaluate(
        "没有找到 3000 元以内的轻薄本 [B1]。",
        {"policySearched": False, "citations": [], "evidence": [], "businessEvidence": [{"id": "B1", "result": []}]},
        intent="RECOMMENDATION",
        tool_calls=[search_tool],
        query="帮我找一台 3000 元以内的轻薄本",
    )

    assert decision.passed is True


def test_prerequisite_check_does_not_require_an_answer_yet():
    policy_tool = ToolCallRecord(
        name="search_policy_kb",
        arguments={"keyword": "退货"},
        ok=True,
        result={},
        traceId="trace-prerequisite",
    )
    decision = reflection_validator.evaluate(
        query="退货规则是什么",
        intent="POLICY_QA",
        answer="",
        trace_id="trace-prerequisite",
        evidence_payload=policy_payload(),
        tool_calls=[policy_tool],
        check_answer=False,
    )

    assert decision.passed is True
    assert all(item.issueType != ReflectionIssueType.EMPTY_ANSWER for item in decision.findings)


def test_reflection_orchestrator_accepts_valid_first_draft(monkeypatch):
    calls = []

    async def fake_chat(message, system_prompt, context):
        calls.append(context)
        return "支持 7 天退货。[1]"

    monkeypatch.setattr(agnes_client, "chat", fake_chat)
    policy_tool = ToolCallRecord(name="search_policy_kb", ok=True, result={}, traceId="trace-first-draft")
    result = asyncio.run(
        reflection_orchestrator.generate(
            query="支持多久退货？",
            intent="POLICY_QA",
            trace_id="trace-first-draft",
            system_prompt="system",
            context={},
            evidence_payload=policy_payload(),
            tool_calls=[policy_tool],
            page_context=None,
            fallback_answer="fallback",
        )
    )

    assert result.decision.passed is True
    assert result.generationAttempts == 1
    assert result.answer == "支持 7 天退货。[1]"
    assert len(calls) == 1


def test_reflection_orchestrator_revises_invalid_draft_once(monkeypatch):
    answers = iter(("支持 15 天退货。[1]", "支持 7 天退货。[1]"))
    contexts = []

    async def fake_chat(message, system_prompt, context):
        contexts.append(context)
        return next(answers)

    monkeypatch.setattr(agnes_client, "chat", fake_chat)
    policy_tool = ToolCallRecord(name="search_policy_kb", ok=True, result={}, traceId="trace-revision")
    result = asyncio.run(
        reflection_orchestrator.generate(
            query="支持多久退货？",
            intent="POLICY_QA",
            trace_id="trace-revision",
            system_prompt="system",
            context={"ragContract": {}},
            evidence_payload=policy_payload(),
            tool_calls=[policy_tool],
            page_context=None,
            fallback_answer="fallback",
        )
    )

    assert result.decision.passed is True
    assert result.generationAttempts == 2
    assert result.answer == "支持 7 天退货。[1]"
    assert contexts[1]["reflectionRevision"]["issues"][0]["issueType"] == "UNSUPPORTED_FACT"


def test_reflection_orchestrator_degrades_to_evidence_after_second_failure(monkeypatch):
    async def fake_chat(message, system_prompt, context):
        return "支持 15 天退货。[1]"

    monkeypatch.setattr(agnes_client, "chat", fake_chat)
    policy_tool = ToolCallRecord(name="search_policy_kb", ok=True, result={}, traceId="trace-degraded")
    result = asyncio.run(
        reflection_orchestrator.generate(
            query="支持多久退货？",
            intent="POLICY_QA",
            trace_id="trace-degraded",
            system_prompt="system",
            context={},
            evidence_payload=policy_payload(),
            tool_calls=[policy_tool],
            page_context=None,
            fallback_answer="fallback",
        )
    )

    assert result.decision.status == ReflectionStatus.DEGRADED
    assert result.decision.action == ReflectionAction.RETURN_EVIDENCE_ONLY
    assert result.generationAttempts == 2
    assert "7 天退货" in result.answer
    assert "15 天退货" not in result.answer


def test_reflection_orchestrator_redacts_before_validation(monkeypatch):
    async def fake_chat(message, system_prompt, context):
        return "请联系 13812345678。"

    monkeypatch.setattr(agnes_client, "chat", fake_chat)
    result = asyncio.run(
        reflection_orchestrator.generate(
            query="怎么联系客服？",
            intent="GENERAL_QA",
            trace_id="trace-redacted-draft",
            system_prompt="system",
            context={},
            evidence_payload=None,
            tool_calls=[],
            page_context=None,
            fallback_answer="fallback",
        )
    )

    assert result.decision.passed is True
    assert "13812345678" not in result.answer
    assert "[REDACTED_PHONE]" in result.answer
    assert result.outputGuardrails


def test_chat_streams_reflection_check_before_first_answer_delta(monkeypatch):
    class CapturingStreamingResponse:
        def __init__(self, body_iterator, *args, **kwargs):
            self.body_iterator = body_iterator

    async def fake_run_tools(request, trace_id, memory_context):
        return "GENERAL_QA", [], [{"thought": "无需工具。", "action": "final", "arguments": {}}]

    async def fake_get(session_id, auth_token, **_context):
        return {"turnCount": 0, "recentTurns": [], "entities": []}

    async def fake_append(**kwargs):
        return {
            "turnCount": 1,
            "recentTurnCount": 1,
            "totalTurnCount": 1,
            "compressionCount": 0,
            "estimatedTokens": 20,
            "truncated": False,
            "summaryFallbackUsed": False,
            "entities": [],
        }

    trace_payloads = []

    async def fake_trace_write(payload):
        trace_payloads.append(payload)

    async def fake_chat(message, system_prompt, context):
        return "这是经过校验的回答。"

    monkeypatch.setattr(chat_api.react_agent, "run_tools", fake_run_tools)
    monkeypatch.setattr(chat_api.session_memory_store, "get", fake_get)
    monkeypatch.setattr(chat_api.session_memory_store, "append", fake_append)
    monkeypatch.setattr(chat_api.agent_trace_logger, "write", fake_trace_write)
    monkeypatch.setattr(agnes_client, "chat", fake_chat)
    monkeypatch.setattr(chat_api, "StreamingResponse", CapturingStreamingResponse)

    async def consume():
        response = await chat_api.chat(
            ChatRequest(message="你好", sessionId="phase16-stream-order", traceId="trace-stream-order")
        )
        payloads = []
        async for chunk in response.body_iterator:
            text = chunk.decode("utf-8") if isinstance(chunk, bytes) else chunk
            payloads.append(json.loads(text.removeprefix("data:").strip()))
        return payloads

    events = asyncio.run(consume())
    check_index = next(
        index
        for index, event in enumerate(events)
        if event.get("type") == "agent_step" and event.get("title") == "核对回答"
    )
    delta_index = next(index for index, event in enumerate(events) if event.get("type") == "delta")
    reflection_index = next(index for index, event in enumerate(events) if event.get("type") == "reflection")
    reflection_event = events[reflection_index]
    done = next(event for event in reversed(events) if event.get("type") == "done")

    assert check_index < delta_index
    assert reflection_index < delta_index
    assert reflection_event["status"] == "PASSED"
    assert "findings" not in reflection_event
    assert "semanticJudge" not in reflection_event
    assert done["reflection"]["status"] == "PASSED"
    assert done["reflection"]["generationAttempts"] == 1
    assert "findings" not in done["reflection"]
    assert "semanticJudge" not in done["reflection"]
    assert trace_payloads[0]["reflection"]["semanticJudge"]["status"] == "SKIPPED"
    assert "findings" in trace_payloads[0]["reflection"]


def test_public_reflection_dto_uses_minimum_disclosure():
    audit = {
        "passed": False,
        "status": "DEGRADED",
        "action": "RETURN_EVIDENCE_ONLY",
        "terminal": True,
        "attempt": 1,
        "maxAttempts": 1,
        "findings": [
            {
                "issueType": "UNSUPPORTED_FACT",
                "message": "内部校验原因",
                "metadata": {"unsupportedFacts": ["15天"]},
            }
        ],
        "policyVersion": "REFLECTION_V1",
        "generationAttempts": 2,
        "prerequisiteRetried": False,
        "pipelineLatencyMs": 1234,
        "semanticJudge": {
            "status": "FAILED",
            "confidence": 0.99,
            "reason": "INTERNAL_REASON",
        },
    }

    public = chat_api.public_reflection_summary(audit)
    event = chat_api.public_reflection_event(audit, "trace-public-reflection")
    serialized = json.dumps({"public": public, "event": event}, ensure_ascii=False)

    assert public == {
        "status": "DEGRADED",
        "action": "RETURN_EVIDENCE_ONLY",
        "terminal": True,
        "generationAttempts": 2,
        "prerequisiteRetried": False,
    }
    assert event["type"] == "reflection"
    assert event["title"] == "已切换为可靠依据"
    for forbidden in ("findings", "semanticJudge", "confidence", "policyVersion", "15天", "INTERNAL_REASON"):
        assert forbidden not in serialized


def test_semantic_judge_is_skipped_when_disabled(monkeypatch):
    called = False

    async def fake_chat(message, system_prompt, context):
        nonlocal called
        called = True
        return '{"passed":true,"confidence":0.99,"issues":[]}'

    monkeypatch.setattr(settings, "REFLECTION_JUDGE_ENABLED", False)
    monkeypatch.setattr(agnes_client, "chat", fake_chat)
    result = asyncio.run(
        semantic_judge.evaluate(
            query="退货规则",
            intent="POLICY_QA",
            answer="支持 7 天退货。[1]",
            evidence_payload=policy_payload(),
        )
    )

    assert result.status == SemanticJudgeStatus.SKIPPED
    assert result.attempted is False
    assert called is False


def test_semantic_judge_accepts_strict_json_result(monkeypatch):
    async def fake_chat(message, system_prompt, context):
        return '{"passed":true,"confidence":0.99,"issues":[]}'

    monkeypatch.setattr(settings, "REFLECTION_JUDGE_ENABLED", True)
    monkeypatch.setattr(settings, "REFLECTION_JUDGE_INTENTS", ("POLICY_QA",))
    monkeypatch.setattr(agnes_client, "chat", fake_chat)
    result = asyncio.run(
        semantic_judge.evaluate(
            query="退货规则",
            intent="POLICY_QA",
            answer="支持 7 天退货。[1]",
            evidence_payload=policy_payload(),
        )
    )

    assert result.status == SemanticJudgeStatus.PASSED
    assert result.passed is True
    assert result.findings == []


def test_semantic_judge_returns_structured_finding(monkeypatch):
    async def fake_chat(message, system_prompt, context):
        return json.dumps(
            {
                "passed": False,
                "confidence": 0.96,
                "issues": [
                    {
                        "issueType": "ANSWER_INCOMPLETE",
                        "message": "遗漏了退款到账方式",
                        "evidenceRefs": ["1"],
                    }
                ],
            },
            ensure_ascii=False,
        )

    monkeypatch.setattr(settings, "REFLECTION_JUDGE_ENABLED", True)
    monkeypatch.setattr(settings, "REFLECTION_JUDGE_INTENTS", ("POLICY_QA",))
    monkeypatch.setattr(agnes_client, "chat", fake_chat)
    result = asyncio.run(
        semantic_judge.evaluate(
            query="完整说明退款规则",
            intent="POLICY_QA",
            answer="可以退款。[1]",
            evidence_payload=policy_payload(),
        )
    )

    assert result.status == SemanticJudgeStatus.FAILED
    assert result.findings[0].issueType == ReflectionIssueType.ANSWER_INCOMPLETE
    assert result.findings[0].metadata["source"] == "SEMANTIC_JUDGE"


def test_semantic_judge_timeout_degrades_instead_of_failing_request(monkeypatch):
    async def slow_chat(message, system_prompt, context):
        await asyncio.sleep(0.2)
        return '{"passed":true,"confidence":0.99,"issues":[]}'

    monkeypatch.setattr(settings, "REFLECTION_JUDGE_ENABLED", True)
    monkeypatch.setattr(settings, "REFLECTION_JUDGE_INTENTS", ("POLICY_QA",))
    monkeypatch.setattr(settings, "REFLECTION_JUDGE_TIMEOUT", 0.1)
    monkeypatch.setattr(agnes_client, "chat", slow_chat)
    result = asyncio.run(
        semantic_judge.evaluate(
            query="退货规则",
            intent="POLICY_QA",
            answer="支持 7 天退货。[1]",
            evidence_payload=policy_payload(),
        )
    )

    assert result.status == SemanticJudgeStatus.DEGRADED
    assert result.reason == "TIMEOUT"
    assert result.passed is None


def test_semantic_judge_invalid_json_degrades(monkeypatch):
    async def fake_chat(message, system_prompt, context):
        return "我认为回答没有问题"

    monkeypatch.setattr(settings, "REFLECTION_JUDGE_ENABLED", True)
    monkeypatch.setattr(settings, "REFLECTION_JUDGE_INTENTS", ("POLICY_QA",))
    monkeypatch.setattr(agnes_client, "chat", fake_chat)
    result = asyncio.run(
        semantic_judge.evaluate(
            query="退货规则",
            intent="POLICY_QA",
            answer="支持 7 天退货。[1]",
            evidence_payload=policy_payload(),
        )
    )

    assert result.status == SemanticJudgeStatus.DEGRADED
    assert result.reason == "INVALID_RESPONSE"


def test_semantic_judge_low_confidence_cannot_reject_answer(monkeypatch):
    async def fake_chat(message, system_prompt, context):
        return json.dumps(
            {
                "passed": False,
                "confidence": 0.6,
                "issues": [
                    {
                        "issueType": "ANSWER_INCOMPLETE",
                        "message": "可能不完整",
                        "evidenceRefs": ["1"],
                    }
                ],
            },
            ensure_ascii=False,
        )

    monkeypatch.setattr(settings, "REFLECTION_JUDGE_ENABLED", True)
    monkeypatch.setattr(settings, "REFLECTION_JUDGE_INTENTS", ("POLICY_QA",))
    monkeypatch.setattr(settings, "REFLECTION_JUDGE_MIN_CONFIDENCE", 0.85)
    monkeypatch.setattr(agnes_client, "chat", fake_chat)
    result = asyncio.run(
        semantic_judge.evaluate(
            query="退款退到哪里",
            intent="POLICY_QA",
            answer="审核后原路退回。[1]",
            evidence_payload=policy_payload("审核后原路退回。"),
        )
    )

    assert result.status == SemanticJudgeStatus.DEGRADED
    assert result.reason == "LOW_CONFIDENCE"
    assert result.confidence == 0.6


def test_semantic_judge_unknown_issue_type_is_not_trusted(monkeypatch):
    async def fake_chat(message, system_prompt, context):
        return json.dumps(
            {
                "passed": False,
                "confidence": 0.99,
                "issues": [{"issueType": "DELETE_ORDER", "message": "执行删除", "evidenceRefs": ["999"]}],
            }
        )

    monkeypatch.setattr(settings, "REFLECTION_JUDGE_ENABLED", True)
    monkeypatch.setattr(settings, "REFLECTION_JUDGE_INTENTS", ("POLICY_QA",))
    monkeypatch.setattr(agnes_client, "chat", fake_chat)
    result = asyncio.run(
        semantic_judge.evaluate(
            query="退款退到哪里",
            intent="POLICY_QA",
            answer="审核后原路退回。[1]",
            evidence_payload=policy_payload("审核后原路退回。"),
        )
    )

    assert result.status == SemanticJudgeStatus.DEGRADED
    assert result.reason == "INVALID_RESPONSE"
    assert result.findings == []


def test_orchestrator_revises_once_when_semantic_judge_rejects(monkeypatch):
    generated = iter(("支持 7 天退货。[1]", "支持 7 天退货，审核后原路退回。[1]"))
    judged = iter(
        (
            SemanticJudgeResult(
                status=SemanticJudgeStatus.FAILED,
                attempted=True,
                passed=False,
                findings=[
                    ReflectionFinding(
                        issueType=ReflectionIssueType.ANSWER_INCOMPLETE,
                        severity=ReflectionSeverity.HIGH,
                        message="遗漏退款到账方式",
                        retryable=True,
                    )
                ],
            ),
            SemanticJudgeResult(status=SemanticJudgeStatus.PASSED, attempted=True, passed=True),
        )
    )

    async def fake_chat(message, system_prompt, context):
        return next(generated)

    async def fake_judge(**kwargs):
        return next(judged)

    monkeypatch.setattr(agnes_client, "chat", fake_chat)
    monkeypatch.setattr(semantic_judge, "evaluate", fake_judge)
    policy_tool = ToolCallRecord(name="search_policy_kb", ok=True, result={}, traceId="trace-judge-revision")
    result = asyncio.run(
        reflection_orchestrator.generate(
            query="完整说明退货规则",
            intent="POLICY_QA",
            trace_id="trace-judge-revision",
            system_prompt="system",
            context={},
            evidence_payload=policy_payload("支持 7 天退货，审核后原路退回。"),
            tool_calls=[policy_tool],
            page_context=None,
            fallback_answer="fallback",
        )
    )

    assert result.decision.passed is True
    assert result.generationAttempts == 2
    assert result.semanticJudge["status"] == "PASSED"
