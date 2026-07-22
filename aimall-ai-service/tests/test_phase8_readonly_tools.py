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
from app.router.intent_router import detect_intent
from app.schemas.chat_schema import ChatRequest
from app.tools.registry import get_tool_definition


def test_phase8_tools_registered_and_require_auth():
    for name in (
        "list_my_coupons",
        "list_coupon_center",
        "list_my_returns",
        "get_return_detail",
        "list_my_addresses",
    ):
        tool = get_tool_definition(name)
        assert tool is not None
        assert tool.requiresAuth is True
        assert "userId" not in tool.parameters.get("properties", {})


def test_coupon_fallback_routes_to_my_coupons():
    agent = ReActAgent()
    request = ChatRequest(message="我的优惠券有哪些", sessionId="s1", pageContext={"pageType": "HOME"})

    action = agent._fallback_action(request, "COUPON_QA", [])

    assert action["action"] == "list_my_coupons"
    assert action["arguments"] == {}


def test_coupon_center_fallback_routes_to_coupon_center():
    agent = ReActAgent()
    request = ChatRequest(message="领券中心有哪些可领取优惠券", sessionId="s1", pageContext={"pageType": "HOME"})

    action = agent._fallback_action(request, "COUPON_QA", [])

    assert action["action"] == "list_coupon_center"
    assert action["arguments"] == {}


def test_return_fallback_routes_to_list_or_detail():
    agent = ReActAgent()
    list_request = ChatRequest(message="我的售后申请有哪些", sessionId="s1", pageContext={"pageType": "HOME"})
    detail_request = ChatRequest(message="查看售后 12 的详情", sessionId="s1", pageContext={"pageType": "HOME"})

    list_action = agent._fallback_action(list_request, "RETURN_QA", [])
    detail_action = agent._fallback_action(detail_request, "RETURN_QA", [])

    assert list_action["action"] == "list_my_returns"
    assert detail_action["action"] == "get_return_detail"
    assert detail_action["arguments"] == {"returnId": 12}


def test_address_fallback_routes_to_addresses():
    agent = ReActAgent()
    request = ChatRequest(message="我的默认收货地址是什么", sessionId="s1", pageContext={"pageType": "HOME"})

    action = agent._fallback_action(request, "ADDRESS_QA", [])

    assert action["action"] == "list_my_addresses"
    assert action["arguments"] == {}


def test_phase8_intent_detection():
    assert detect_intent("我的优惠券有哪些", "HOME") == "COUPON_QA"
    assert detect_intent("我的售后申请", "HOME") == "RETURN_QA"
    assert detect_intent("我的收货地址", "HOME") == "ADDRESS_QA"


def test_named_product_price_and_stock_question_prefers_product_qa():
    assert detect_intent("轻薄笔记本 B2 的价格和库存是多少？", "GENERAL") == "PRODUCT_QA"
    assert detect_intent("购物车库存规则是什么？", "GENERAL") == "POLICY_QA"


def test_return_detail_requires_return_id():
    agent = ReActAgent()
    request = ChatRequest(message="查看售后详情", sessionId="s1", pageContext={"pageType": "HOME"})

    error = agent._guard_tool_call("get_return_detail", {}, request, [], [])

    assert error is not None
    assert "returnId" in error


if __name__ == "__main__":
    tests = [
        test_phase8_tools_registered_and_require_auth,
        test_coupon_fallback_routes_to_my_coupons,
        test_coupon_center_fallback_routes_to_coupon_center,
        test_return_fallback_routes_to_list_or_detail,
        test_address_fallback_routes_to_addresses,
        test_phase8_intent_detection,
        test_return_detail_requires_return_id,
    ]
    for test in tests:
        test()
    print("phase8 readonly tool checks passed")
