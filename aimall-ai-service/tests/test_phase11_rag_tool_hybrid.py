import sys
import types


httpx_stub = types.ModuleType("httpx")


class AsyncClientStub:
    def __init__(self, *args, **kwargs):
        self.is_closed = False

    async def aclose(self):
        self.is_closed = True


httpx_stub.AsyncClient = AsyncClientStub
sys.modules.setdefault("httpx", httpx_stub)

from app.agent.react_agent import ReActAgent
from app.api.chat_api import build_rag_citation_payload, should_refuse_rag_generation
from app.schemas.chat_schema import ChatRequest
from app.schemas.tool_schema import ToolCallRecord
from app.tools.tool_router import select_candidate_tool_names


def test_order_intent_exposes_order_and_policy_tools():
    tools = select_candidate_tool_names("ORDER_QA", None)

    assert "get_my_order_detail" in tools
    assert "search_policy_kb" in tools


def test_mixed_intents_force_business_tool_before_policy_search():
    agent = ReActAgent()

    order_action = agent._scripted_next_action(
        ChatRequest(
            message="订单号 AIM20260714000000000005 已关闭，关闭后还能恢复吗",
            sessionId="phase11-order-first",
            pageContext={"pageType": "GENERAL"},
        ),
        "ORDER_QA",
        [],
    )
    product_action = agent._scripted_next_action(
        ChatRequest(
            message="这个商品支持退货吗",
            sessionId="phase11-product-first",
            pageContext={"pageType": "PRODUCT_DETAIL", "productId": 1103},
        ),
        "PRODUCT_QA",
        [],
    )
    return_action = agent._scripted_next_action(
        ChatRequest(
            message="售后单 1 审核通过后多久退款",
            sessionId="phase11-return-first",
            pageContext={"pageType": "GENERAL"},
        ),
        "RETURN_QA",
        [],
    )

    assert order_action["action"] == "get_my_order_detail"
    assert product_action["action"] == "get_product_detail"
    assert return_action["action"] == "get_return_detail"


def test_return_status_does_not_need_policy_followup():
    agent = ReActAgent()

    assert agent._needs_policy_followup("售后单 1 现在处理到哪一步了") is False
    assert agent._needs_policy_followup("售后单 1 审核通过后多久退款") is True


def test_order_detail_then_policy_scripted_action():
    agent = ReActAgent()
    request = ChatRequest(
        message="订单号 AIM202607120001 能退款吗",
        sessionId="phase11",
        pageContext={"pageType": "GENERAL"},
    )
    steps = [
        {
            "action": "get_my_order_detail",
            "arguments": {"orderSn": "AIM202607120001"},
            "observation": {
                "ok": True,
                "result": {"orderSn": "AIM202607120001", "statusText": "待发货", "payAmount": 3999},
            },
        }
    ]

    action = agent._scripted_next_action(request, "ORDER_QA", steps)

    assert action is not None
    assert action["action"] == "search_policy_kb"
    assert action["arguments"] == {"keyword": request.message, "topK": 5}


def test_plain_order_status_does_not_force_policy_search():
    agent = ReActAgent()
    request = ChatRequest(
        message="订单号 AIM202607120001 现在是什么状态",
        sessionId="phase11",
        pageContext={"pageType": "GENERAL"},
    )
    steps = [
        {
            "action": "get_my_order_detail",
            "arguments": {"orderSn": "AIM202607120001"},
            "observation": {"ok": True, "result": {"statusText": "待发货"}},
        }
    ]

    action = agent._scripted_next_action(request, "ORDER_QA", steps)

    assert action is not None
    assert action["action"] == "final"


def test_plain_order_status_keeps_business_evidence_without_rag_refusal():
    payload = build_rag_citation_payload(
        [
            ToolCallRecord(
                name="get_my_order_detail",
                arguments={"orderSn": "AIM20260714000000000001"},
                ok=True,
                result={"statusText": "待支付", "orderSn": "AIM20260714000000000001"},
                traceId="trace-business-only",
            )
        ],
        "trace-business-only",
    )

    assert payload is not None
    assert payload["policySearched"] is False
    assert payload["citations"] == []
    assert payload["businessEvidence"][0]["id"] == "B1"
    assert payload["businessEvidence"][0]["toolName"] == "get_my_order_detail"
    assert should_refuse_rag_generation("ORDER_QA", payload) is False


def test_product_detail_then_policy_scripted_action():
    agent = ReActAgent()
    request = ChatRequest(
        message="这个商品支持退货吗",
        sessionId="phase11-product",
        pageContext={"pageType": "PRODUCT_DETAIL", "productId": 1001},
    )
    steps = [
        {
            "action": "get_product_detail",
            "arguments": {"productId": 1001},
            "observation": {"ok": True, "result": {"productId": 1001, "name": "测试商品"}},
        }
    ]

    action = agent._scripted_next_action(request, "PRODUCT_QA", steps)

    assert action is not None
    assert action["action"] == "search_policy_kb"


def test_return_detail_then_policy_scripted_action():
    agent = ReActAgent()
    request = ChatRequest(
        message="售后单 12 退款多久到账",
        sessionId="phase11-return",
        pageContext={"pageType": "GENERAL"},
    )
    steps = [
        {
            "action": "get_return_detail",
            "arguments": {"returnId": 12},
            "observation": {"ok": True, "result": {"returnId": 12, "statusText": "已通过"}},
        }
    ]

    action = agent._scripted_next_action(request, "RETURN_QA", steps)

    assert action is not None
    assert action["action"] == "search_policy_kb"


if __name__ == "__main__":
    test_order_intent_exposes_order_and_policy_tools()
    test_order_detail_then_policy_scripted_action()
    test_plain_order_status_does_not_force_policy_search()
    test_plain_order_status_keeps_business_evidence_without_rag_refusal()
    test_product_detail_then_policy_scripted_action()
    test_return_detail_then_policy_scripted_action()
    print("phase11 rag + tool hybrid checks passed")
