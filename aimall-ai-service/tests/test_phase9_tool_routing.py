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
from app.schemas.chat_schema import ChatRequest, PageContext
from app.tools.tool_router import build_tool_routing_info, list_candidate_tool_definitions, select_candidate_tool_names


def test_product_intent_routes_only_product_tools():
    tools = select_candidate_tool_names("RECOMMENDATION", PageContext(pageType="HOME"))

    assert "search_products" in tools
    assert "get_product_detail" in tools
    assert "list_my_orders" not in tools
    assert "list_my_addresses" not in tools


def test_order_intent_routes_order_and_policy_tools():
    tools = select_candidate_tool_names("ORDER_QA", PageContext(pageType="HOME"))

    assert tools == [
        "list_my_orders",
        "get_my_order_detail",
        "cancel_order_confirmed",
        "apply_return_confirmed",
        "search_policy_kb",
    ]


def test_phase8_intents_route_to_their_groups():
    assert select_candidate_tool_names("COUPON_QA", None) == [
        "list_my_coupons",
        "list_coupon_center",
        "claim_coupon_confirmed",
    ]
    assert select_candidate_tool_names("ADDRESS_QA", None) == ["list_my_addresses"]
    assert select_candidate_tool_names("RETURN_QA", None) == [
        "list_my_returns",
        "get_return_detail",
        "apply_return_confirmed",
        "search_policy_kb",
    ]


def test_page_context_assists_routing_without_duplicates():
    tools = select_candidate_tool_names("PRODUCT_QA", PageContext(pageType="PRODUCT_DETAIL", productId=1))

    assert tools.count("get_product_detail") == 1
    assert tools.count("get_product_skus") == 1


def test_candidate_tool_definitions_are_filtered():
    definitions = list_candidate_tool_definitions("COUPON_QA", None)
    names = [item["name"] for item in definitions]

    assert names == ["list_my_coupons", "list_coupon_center", "claim_coupon_confirmed"]


def test_guard_rejects_tool_outside_candidate_list():
    agent = ReActAgent()
    request = ChatRequest(message="我的优惠券有哪些", sessionId="s1", pageContext={"pageType": "HOME"})

    error = agent._guard_tool_call(
        "search_products",
        {"keyword": "优惠券"},
        request,
        [],
        [],
        ["list_my_coupons", "list_coupon_center"],
    )

    assert error is not None
    assert "候选工具" in error


def test_plan_context_records_routing_info_shape():
    info = build_tool_routing_info("ORDER_QA", PageContext(pageType="ORDER_DETAIL", orderId=1))

    assert info["strategy"] == "intent_page_rule_v1"
    assert info["intent"] == "ORDER_QA"
    assert info["pageType"] == "ORDER_DETAIL"
    assert "get_my_order_detail" in info["candidateTools"]


if __name__ == "__main__":
    tests = [
        test_product_intent_routes_only_product_tools,
        test_order_intent_routes_order_and_policy_tools,
        test_phase8_intents_route_to_their_groups,
        test_page_context_assists_routing_without_duplicates,
        test_candidate_tool_definitions_are_filtered,
        test_guard_rejects_tool_outside_candidate_list,
        test_plan_context_records_routing_info_shape,
    ]
    for test in tests:
        test()
    print("phase9 tool routing checks passed")
