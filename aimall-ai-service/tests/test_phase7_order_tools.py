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
from app.schemas.chat_schema import ChatRequest
from app.tools.registry import get_tool_definition


def test_order_tools_registered():
    list_tool = get_tool_definition("list_my_orders")
    detail_tool = get_tool_definition("get_my_order_detail")

    assert list_tool is not None
    assert list_tool.requiresAuth is True
    assert "userId" not in list_tool.parameters.get("properties", {})

    assert detail_tool is not None
    assert detail_tool.requiresAuth is True
    assert "userId" not in detail_tool.parameters.get("properties", {})
    assert "orderId" in detail_tool.parameters.get("properties", {})
    assert "orderSn" in detail_tool.parameters.get("properties", {})


def test_order_list_fallback_action():
    agent = ReActAgent()
    request = ChatRequest(message="我的订单到哪了", sessionId="s1", pageContext={"pageType": "ORDER_LIST"})

    action = agent._fallback_action(request, "ORDER_QA", [])

    assert action["action"] == "list_my_orders"
    assert action["arguments"] == {"limit": 5}


def test_order_detail_fallback_action():
    agent = ReActAgent()
    request = ChatRequest(
        message="这个订单现在是什么状态",
        sessionId="s1",
        pageContext={"pageType": "ORDER_DETAIL", "orderId": 1001},
    )

    action = agent._fallback_action(request, "ORDER_QA", [])

    assert action["action"] == "get_my_order_detail"
    assert action["arguments"] == {"orderId": 1001}


def test_order_tool_rejects_model_user_id():
    agent = ReActAgent()
    request = ChatRequest(message="查订单", sessionId="s1", pageContext={"pageType": "ORDER_LIST"})

    error = agent._guard_tool_call("list_my_orders", {"userId": 1}, request, [], [])

    assert error is not None
    assert "用户 ID" in error


def test_order_detail_by_order_sn_from_any_page():
    agent = ReActAgent()
    request = ChatRequest(
        message="订单号 AIM20260708205327573654 查看这个订单的详情",
        sessionId="s1",
        pageContext={"pageType": "HOME"},
    )

    action = agent._fallback_action(request, "ORDER_QA", [])
    normalized = agent._normalize_action(action, request)
    error = agent._guard_tool_call(normalized["action"], normalized["arguments"], request, [normalized], [])

    assert normalized["action"] == "get_my_order_detail"
    assert normalized["arguments"] == {"orderSn": "AIM20260708205327573654"}
    assert error is None


def test_non_numeric_order_id_is_normalized_to_order_sn():
    agent = ReActAgent()
    request = ChatRequest(
        message="订单号 AIM20260708205327573654 查看这个订单的详情",
        sessionId="s1",
        pageContext={"pageType": "HOME"},
    )

    action = {
        "thought": "用户提供了订单号，查询订单详情。",
        "action": "get_my_order_detail",
        "arguments": {"orderId": "AIM20260708205327573654"},
    }
    normalized = agent._normalize_action(action, request)

    assert normalized["arguments"] == {"orderSn": "AIM20260708205327573654"}


if __name__ == "__main__":
    tests = [
        test_order_tools_registered,
        test_order_list_fallback_action,
        test_order_detail_fallback_action,
        test_order_tool_rejects_model_user_id,
        test_order_detail_by_order_sn_from_any_page,
        test_non_numeric_order_id_is_normalized_to_order_sn,
    ]
    for test in tests:
        test()
    print("phase7 order tool checks passed")
