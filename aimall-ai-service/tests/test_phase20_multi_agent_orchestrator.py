import asyncio
import app.agent.react_agent as react_module

from app.config.settings import settings
from app.multi_agent.orchestrator import MultiAgentOrchestrator
from app.multi_agent.supervisor import MultiAgentSupervisor
from app.schemas.chat_schema import ChatRequest, PageContext
from app.schemas.tool_schema import ToolCallRecord
from app.api.chat_api import build_timeline_events
from app.agent.react_agent import ReActAgent


class FakeReact:
    def __init__(self):
        self.calls = []

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
        self.calls.append((list(allowed_tool_names or []), strict_allowed_tools))
        tool_name = "search_policy_kb" if allowed_tool_names == ["search_policy_kb"] else "get_product_detail"
        return "PRODUCT_QA", [ToolCallRecord(name=tool_name, ok=True, traceId=trace_id)], [
            {"thought": "safe step", "action": tool_name, "arguments": {}, "candidateTools": allowed_tool_names}
        ]


def test_orchestrator_runs_specialists_in_supervisor_order(monkeypatch):
    monkeypatch.setattr(settings, "MULTI_AGENT_ENABLED", True)
    react = FakeReact()
    orchestrator = MultiAgentOrchestrator(react, MultiAgentSupervisor())

    result = asyncio.run(
        orchestrator.run_tools(
            ChatRequest(
                message="这台笔记本的退货规则是什么？",
                sessionId="phase20-orchestrator",
                pageContext=PageContext(pageType="PRODUCT_DETAIL", productId=1001),
            ),
            "trace-phase20",
            {},
        )
    )

    assert [step["agent"] for step in result.steps] == ["PRODUCT_SPECIALIST", "POLICY_SPECIALIST"]
    assert react.calls[0][1] is True
    assert react.calls[1][0] == ["search_policy_kb"]
    assert [call.name for call in result.tool_calls] == ["get_product_detail", "search_policy_kb"]
    assert result.used_legacy_fallback is False


def test_orchestrator_uses_legacy_agent_when_disabled(monkeypatch):
    monkeypatch.setattr(settings, "MULTI_AGENT_ENABLED", False)
    react = FakeReact()
    orchestrator = MultiAgentOrchestrator(react, MultiAgentSupervisor())

    result = asyncio.run(orchestrator.run_tools(ChatRequest(message="笔记本", sessionId="phase20-legacy"), "trace-legacy", {}))

    assert result.used_legacy_fallback is True
    assert result.supervisor_plan.fallbackReason == "MULTI_AGENT_DISABLED"
    assert react.calls == [([], False)]


def test_orchestrator_resolves_product_pronoun_before_supervisor_planning(monkeypatch):
    monkeypatch.setattr(settings, "MULTI_AGENT_ENABLED", True)
    react = FakeReact()
    orchestrator = MultiAgentOrchestrator(react, MultiAgentSupervisor())

    result = asyncio.run(
        orchestrator.run_tools(
            ChatRequest(
                message="它支持七天无理由退货吗？",
                sessionId="phase20-product-memory",
            ),
            "trace-product-memory",
            {
                "entities": [
                    {"kind": "product", "entity_id": "1002", "label": "轻薄笔记本 B2", "ordinal": 1}
                ],
                "recentTurns": [],
            },
        )
    )

    assert result.intent == "PRODUCT_QA"
    assert [item.specialist.value for item in result.supervisor_plan.delegations] == [
        "PRODUCT_SPECIALIST", "POLICY_SPECIALIST"
    ]
    assert react.calls[0][0][0] == "search_products"
    assert react.calls[1][0] == ["search_policy_kb"]


def test_orchestrator_resolves_order_pronoun_before_supervisor_planning(monkeypatch):
    monkeypatch.setattr(settings, "MULTI_AGENT_ENABLED", True)
    react = FakeReact()
    orchestrator = MultiAgentOrchestrator(react, MultiAgentSupervisor())

    result = asyncio.run(
        orchestrator.run_tools(
            ChatRequest(message="它什么时候发货？", sessionId="phase20-order-memory"),
            "trace-order-memory",
            {
                "entities": [
                    {
                        "kind": "order",
                        "entity_id": "AIM20260714000000000002",
                        "label": "AIM20260714000000000002",
                        "ordinal": 1,
                    }
                ],
                "recentTurns": [],
            },
        )
    )

    assert result.intent == "ORDER_QA"
    assert [item.specialist.value for item in result.supervisor_plan.delegations] == [
        "ORDER_SPECIALIST", "POLICY_SPECIALIST"
    ]
    assert "get_my_order_detail" in react.calls[0][0]
    assert react.calls[1][0] == ["search_policy_kb"]


def test_timeline_exposes_safe_specialist_delegation_summary():
    events = build_timeline_events(
        intent="PRODUCT_QA",
        agent_steps=[
            {
                "agent": "PRODUCT_SPECIALIST",
                "thought": "safe step",
                "action": "get_product_detail",
                "arguments": {"productId": 1001},
                "candidateTools": ["get_product_detail"],
            }
        ],
        tool_calls=[ToolCallRecord(name="get_product_detail", arguments={"productId": 1001}, ok=True, traceId="trace")],
        trace_id="trace",
    )

    delegation = next(event for event in events if event.get("agent") == "PRODUCT_SPECIALIST")
    assert delegation["type"] == "agent_step"
    assert "PRODUCT_SPECIALIST" not in delegation["content"]
    serialized = str(events)
    assert "safe step" not in serialized
    assert "candidateTools" not in serialized
    assert "productId" not in serialized


def test_public_agent_steps_do_not_expose_internal_specialist_reasoning():
    from app.api.chat_api import public_agent_steps

    public = public_agent_steps([{
        "step": 1,
        "thought": "hidden planner reasoning",
        "action": "get_product_detail",
        "arguments": {"productId": 1001},
        "candidateTools": ["get_product_detail"],
        "agent": "PRODUCT_SPECIALIST",
        "delegationSequence": 1,
    }])

    assert public == [{
        "step": 1,
        "action": "get_product_detail",
        "agent": "PRODUCT_SPECIALIST",
        "delegationSequence": 1,
    }]


def test_strict_specialist_boundary_stops_cross_domain_tool_before_execution(monkeypatch):
    agent = ReActAgent()

    async def cross_domain_action(*args, **kwargs):
        return {"thought": "attempt policy", "action": "search_policy_kb", "arguments": {"keyword": "退货"}}

    monkeypatch.setattr(agent, "_plan_action", cross_domain_action)
    result = asyncio.run(
        agent.run_tools(
            ChatRequest(message="商品问题", sessionId="phase20-boundary"),
            "trace-boundary",
            {},
            allowed_tool_names=["get_product_detail"],
            strict_allowed_tools=True,
        )
    )

    assert result[1] == []
    assert result[2][0]["action"] == "final"


def test_policy_specialist_ignores_memory_business_action_and_retrieves_policy():
    agent = ReActAgent()
    request = ChatRequest(
        message="它支持七天无理由退货吗？",
        sessionId="phase20-policy-memory-boundary",
    )
    memory = {
        "entities": [
            {"kind": "product", "entity_id": "1002", "label": "轻薄笔记本 B2", "ordinal": 1}
        ],
        "recentTurns": [],
    }

    action = agent._scripted_next_action(
        request,
        "POLICY_QA",
        [],
        memory,
        ["search_policy_kb"],
    )

    assert action["action"] == "search_policy_kb"


def test_orchestrator_stops_later_specialists_after_first_specialist_failure(monkeypatch):
    monkeypatch.setattr(settings, "MULTI_AGENT_ENABLED", True)

    class FailingReact(FakeReact):
        async def run_tools(self, *args, **kwargs):
            self.calls.append((list(kwargs.get("allowed_tool_names") or []), kwargs.get("strict_allowed_tools")))
            return "PRODUCT_QA", [ToolCallRecord(name="get_product_detail", ok=False, traceId="trace")], []

    react = FailingReact()
    orchestrator = MultiAgentOrchestrator(react, MultiAgentSupervisor())
    result = asyncio.run(
        orchestrator.run_tools(
            ChatRequest(
                message="这件商品的退货规则是什么？",
                sessionId="phase20-stop",
                pageContext=PageContext(pageType="PRODUCT_DETAIL", productId=1101),
            ),
            "trace-stop",
            {},
        )
    )

    assert len(react.calls) == 1
    assert result.supervisor_plan.delegations[0].status.value == "FAILED"
    assert result.supervisor_plan.delegations[1].status.value == "SKIPPED"


def test_orchestrator_keeps_supervisor_global_intent_when_policy_specialist_uses_local_intent(monkeypatch):
    monkeypatch.setattr(settings, "MULTI_AGENT_ENABLED", True)

    class LocalPolicyIntentReact(FakeReact):
        async def run_tools(self, *args, **kwargs):
            allowed = list(kwargs.get("allowed_tool_names") or [])
            intent = kwargs.get("intent_override") or "ORDER_QA"
            return intent, [], [{"thought": "safe", "action": "final", "arguments": {}, "candidateTools": allowed}]

    result = asyncio.run(
        MultiAgentOrchestrator(LocalPolicyIntentReact(), MultiAgentSupervisor()).run_tools(
            ChatRequest(message="我的订单什么时候自动关闭？", sessionId="phase20-global-intent"),
            "trace-global-intent",
            {},
        )
    )

    assert result.intent == "ORDER_QA"


def test_react_prevents_duplicate_tool_call_before_executor(monkeypatch):
    agent = ReActAgent()
    execute_count = 0
    arguments = {"productId": 1001}

    async def repeated_action(*_args, **_kwargs):
        return {"thought": "repeat", "action": "get_product_detail", "arguments": arguments}

    async def execute(*_args, **_kwargs):
        nonlocal execute_count
        execute_count += 1
        return ToolCallRecord(name="get_product_detail", arguments=arguments, ok=True, traceId="trace")

    monkeypatch.setattr(agent, "_plan_action", repeated_action)
    monkeypatch.setattr(react_module.tool_executor, "execute", execute)
    key = ReActAgent.tool_call_key("get_product_detail", arguments)
    result = asyncio.run(agent.run_tools(
        ChatRequest(message="商品详情", sessionId="phase20-dedupe"),
        "trace",
        {},
        allowed_tool_names=["get_product_detail"],
        strict_allowed_tools=True,
        excluded_tool_call_keys={key},
    ))

    assert execute_count == 0
    assert result[1] == []
    assert any(step.get("duplicateCallPrevented") for step in result[2])


def test_orchestrator_enforces_six_call_budget_across_specialists(monkeypatch):
    monkeypatch.setattr(settings, "MULTI_AGENT_ENABLED", True)

    class BudgetReact(FakeReact):
        async def run_tools(self, *args, **kwargs):
            self.calls.append((list(kwargs.get("allowed_tool_names") or []), kwargs.get("strict_allowed_tools")))
            return "PRODUCT_QA", [
                ToolCallRecord(name="get_product_detail", arguments={"productId": index}, ok=True, traceId="trace")
                for index in range(6)
            ], []

    react = BudgetReact()
    result = asyncio.run(MultiAgentOrchestrator(react, MultiAgentSupervisor()).run_tools(
        ChatRequest(
            message="这件商品的退货规则是什么？",
            sessionId="phase20-budget",
            pageContext=PageContext(pageType="PRODUCT_DETAIL", productId=1101),
        ),
        "trace",
        {},
    ))

    assert len(result.tool_calls) == 6
    assert len(react.calls) == 1
    assert result.supervisor_plan.delegations[1].status.value == "SKIPPED"
