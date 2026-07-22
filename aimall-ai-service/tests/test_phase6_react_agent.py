import asyncio
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

from app.agent.react_agent import MAX_REACT_STEPS, MAX_TOOL_CALLS, ReActAgent
from app.schemas.chat_schema import ChatRequest


def test_phase6_limits_are_configured():
    assert MAX_REACT_STEPS == 4
    assert MAX_TOOL_CALLS == 6


def test_repeat_tool_call_is_blocked():
    agent = ReActAgent()
    request = ChatRequest(message="帮我找轻薄本", sessionId="s1", pageContext={"pageType": "GENERAL"})
    steps = [
        {
            "action": "search_products",
            "arguments": {"keyword": "轻薄本", "maxPrice": 3000, "inStock": True, "limit": 10},
        },
        {
            "action": "search_products",
            "arguments": {"keyword": "轻薄本", "maxPrice": 3000, "inStock": True, "limit": 10},
        },
    ]

    error = agent._guard_tool_call(
        "search_products",
        {"keyword": "轻薄本", "maxPrice": 3000, "inStock": True, "limit": 10},
        request,
        steps,
        [],
    )

    assert error is not None
    assert "重复工具调用" in error


def test_keyword_and_budget_extraction():
    agent = ReActAgent()
    assert agent._extract_budget("帮我找一台 3000 元内的轻薄本") == 3000
    assert agent._clean_product_keyword("帮我找一台 3000 元内的轻薄本") == "轻薄本"


def test_search_then_detail_scripted_action():
    agent = ReActAgent()
    request = ChatRequest(message="帮我找轻薄本", sessionId="s1", pageContext={"pageType": "GENERAL"})
    steps = [
        {
            "action": "search_products",
            "arguments": {"keyword": "轻薄本", "inStock": True, "limit": 10},
            "observation": {
                "ok": True,
                "resultCount": 1,
                "resultPreview": [{"productId": 1001, "name": "轻薄本 A"}],
            },
        }
    ]

    action = agent._scripted_next_action(request, "RECOMMENDATION", steps)

    assert action is not None
    assert action["action"] == "get_product_detail"
    assert action["arguments"] == {"productId": 1001}


if __name__ == "__main__":
    tests = [
        test_phase6_limits_are_configured,
        test_repeat_tool_call_is_blocked,
        test_keyword_and_budget_extraction,
        test_search_then_detail_scripted_action,
    ]
    for test in tests:
        result = test()
        if asyncio.iscoroutine(result):
            asyncio.run(result)
    print("phase6 react agent checks passed")
