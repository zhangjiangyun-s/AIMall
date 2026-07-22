import asyncio

from app.config.settings import settings, validate_multi_agent_settings
from app.multi_agent.contracts import SpecialistId
from app.multi_agent.contracts import Delegation
from app.multi_agent.specialists import CapabilityScopedSpecialist
from app.multi_agent.supervisor import multi_agent_supervisor
from app.schemas.chat_schema import PageContext
from app.schemas.chat_schema import ChatRequest
from app.agent.react_agent import ReActAgent


def test_supervisor_preserves_legacy_path_when_feature_is_disabled(monkeypatch):
    monkeypatch.setattr(settings, "MULTI_AGENT_ENABLED", False)

    plan = multi_agent_supervisor.plan(intent="PRODUCT_QA", message="这台笔记本多少钱？")

    assert plan.fallbackToLegacy is True
    assert plan.fallbackReason == "MULTI_AGENT_DISABLED"
    assert plan.delegations == []


def test_supervisor_orders_product_before_policy_for_hybrid_product_question(monkeypatch):
    monkeypatch.setattr(settings, "MULTI_AGENT_ENABLED", True)
    monkeypatch.setattr(settings, "MULTI_AGENT_MAX_DELEGATIONS", 3)

    plan = multi_agent_supervisor.plan(intent="PRODUCT_QA", message="这台笔记本支持七天退货吗？")

    assert [item.specialist for item in plan.delegations] == [SpecialistId.PRODUCT, SpecialistId.POLICY]
    assert plan.delegations[0].allowedTools == [
        "search_products", "get_product_detail", "get_product_skus", "compare_products", "add_to_cart_confirmed"
    ]
    assert plan.delegations[1].allowedTools == ["search_policy_kb"]


def test_supervisor_orders_order_before_policy_for_hybrid_order_question(monkeypatch):
    monkeypatch.setattr(settings, "MULTI_AGENT_ENABLED", True)
    monkeypatch.setattr(settings, "MULTI_AGENT_MAX_DELEGATIONS", 3)

    plan = multi_agent_supervisor.plan(intent="ORDER_QA", message="订单 AIM20260708205327573654 为什么还没发货，规则是什么？")

    assert [item.specialist for item in plan.delegations] == [SpecialistId.ORDER, SpecialistId.POLICY]
    assert "get_my_order_detail" in plan.delegations[0].allowedTools
    assert "search_policy_kb" not in plan.delegations[0].allowedTools


def test_supervisor_does_not_add_policy_specialist_to_direct_confirmed_write(monkeypatch):
    monkeypatch.setattr(settings, "MULTI_AGENT_ENABLED", True)

    plan = multi_agent_supervisor.plan(
        intent="ORDER_QA",
        message="取消订单 AIM20260714000000000001",
    )

    assert [item.specialist for item in plan.delegations] == [SpecialistId.ORDER]
    assert "cancel_order_confirmed" in plan.delegations[0].allowedTools
    assert all(item.specialist != SpecialistId.POLICY for item in plan.delegations)


def test_supervisor_preserves_existing_order_policy_followup_terms(monkeypatch):
    monkeypatch.setattr(settings, "MULTI_AGENT_ENABLED", True)

    plan = multi_agent_supervisor.plan(intent="ORDER_QA", message="我的订单什么时候自动关闭？")

    assert [item.specialist for item in plan.delegations] == [SpecialistId.ORDER, SpecialistId.POLICY]


def test_supervisor_keeps_policy_agent_isolated(monkeypatch):
    monkeypatch.setattr(settings, "MULTI_AGENT_ENABLED", True)

    plan = multi_agent_supervisor.plan(intent="POLICY_QA", message="退款规则")

    assert len(plan.delegations) == 1
    assert plan.delegations[0].specialist == SpecialistId.POLICY
    assert plan.delegations[0].allowedTools == ["search_policy_kb"]


def test_supervisor_uses_page_context_for_general_detail_request(monkeypatch):
    monkeypatch.setattr(settings, "MULTI_AGENT_ENABLED", True)

    plan = multi_agent_supervisor.plan(
        intent="GENERAL_QA",
        message="这个怎么样？",
        page_context=PageContext(pageType="PRODUCT_DETAIL", productId=1001),
    )

    assert [item.specialist for item in plan.delegations] == [SpecialistId.PRODUCT]


def test_specialist_passes_only_supervisor_approved_tools(monkeypatch):
    monkeypatch.setattr(settings, "MULTI_AGENT_ENABLED", True)
    plan = multi_agent_supervisor.plan(intent="POLICY_QA", message="退款规则")
    received = {}

    class FakeReact:
        async def run_tools(
            self,
            request,
            trace_id,
            memory_context,
            allowed_tool_names=None,
                strict_allowed_tools=False,
                intent_override=None,
                excluded_tool_call_keys=None,
                max_tool_calls=6,
            ):
            received["allowed"] = allowed_tool_names
            received["strict"] = strict_allowed_tools
            received["intentOverride"] = intent_override
            return "POLICY_QA", [], []

    specialist = CapabilityScopedSpecialist(SpecialistId.POLICY, FakeReact())
    result = asyncio.run(
        specialist.run(
            request=ChatRequest(message="退款规则", sessionId="phase20"),
            trace_id="trace-phase20",
            memory_context={},
            delegation=plan.delegations[0],
        )
    )

    assert received["allowed"] == ["search_policy_kb"]
    assert received["strict"] is True
    assert received["intentOverride"] == "POLICY_QA"
    assert result.status.value == "COMPLETED"


def test_specialist_rejects_supervisor_tool_outside_immutable_capabilities():
    delegation = Delegation(
        specialist=SpecialistId.POLICY,
        sequence=1,
        reason="malformed supervisor plan",
        allowedTools=["cancel_order_confirmed"],
    )
    called = False

    class FakeReact:
        async def run_tools(self, *_args, **_kwargs):
            nonlocal called
            called = True
            return "POLICY_QA", [], []

    specialist = CapabilityScopedSpecialist(SpecialistId.POLICY, FakeReact())
    try:
        asyncio.run(specialist.run(ChatRequest(message="退款规则", sessionId="phase20"), "trace", {}, delegation))
    except ValueError as exc:
        assert "outside specialist capabilities" in str(exc)
    else:
        raise AssertionError("cross-domain delegation must be rejected")
    assert called is False


def test_policy_specialist_has_deterministic_first_retrieval_action():
    request = ChatRequest(
        message="订单付款后什么时候发货？",
        sessionId="phase20-policy-scripted",
    )

    action = ReActAgent()._scripted_next_action(request, "POLICY_QA", [], {})

    assert action["action"] == "search_policy_kb"
    assert action["arguments"] == {"keyword": request.message, "topK": 5}


def test_multi_agent_configuration_rejects_out_of_contract_delegation_limit(monkeypatch):
    monkeypatch.setattr(settings, "MULTI_AGENT_MAX_DELEGATIONS", 4)

    try:
        validate_multi_agent_settings()
    except RuntimeError as exc:
        assert "between 1 and 3" in str(exc)
    else:
        raise AssertionError("delegation limit above contract must be rejected")
