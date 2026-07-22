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

fastapi_stub = types.ModuleType("fastapi")
responses_stub = types.ModuleType("fastapi.responses")


class APIRouterStub:
    def __init__(self, *args, **kwargs):
        pass

    def post(self, *args, **kwargs):
        def decorator(func):
            return func

        return decorator


class StreamingResponseStub:
    def __init__(self, *args, **kwargs):
        pass


fastapi_stub.APIRouter = APIRouterStub


class HTTPExceptionStub(Exception):
    def __init__(self, status_code: int, detail: str):
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail


fastapi_stub.HTTPException = HTTPExceptionStub
responses_stub.StreamingResponse = StreamingResponseStub
sys.modules.setdefault("fastapi", fastapi_stub)
sys.modules.setdefault("fastapi.responses", responses_stub)

from app.agent.react_agent import ReActAgent
from app.api.chat_api import summarize_tool_result
from app.schemas.chat_schema import ChatRequest
from app.schemas.tool_schema import ToolCallRecord
from app.tools.registry import get_tool_definition
from app.tools.tool_router import select_candidate_tool_names


def test_policy_kb_tool_registered():
    tool = get_tool_definition("search_policy_kb")

    assert tool is not None
    assert "引用来源" in tool.description
    assert "topK" in tool.parameters.get("properties", {})
    assert get_tool_definition("search_knowledge") is None


def test_policy_intent_routes_to_policy_kb_only():
    tools = select_candidate_tool_names("POLICY_QA", None)

    assert tools == ["search_policy_kb"]


def test_policy_fallback_uses_policy_kb():
    agent = ReActAgent()
    request = ChatRequest(message="退货规则是什么", sessionId="s1", pageContext={"pageType": "HOME"})

    action = agent._fallback_action(request, "POLICY_QA", [])

    assert action["action"] == "search_policy_kb"
    assert action["arguments"] == {"keyword": "退货规则是什么", "topK": 5}


def test_policy_tool_result_summary_includes_titles():
    record = ToolCallRecord(
        name="search_policy_kb",
        arguments={"keyword": "退货"},
        ok=True,
        result=[
            {"title": "退货规则", "source": "POLICY#1", "snippet": "7 天内可申请无理由退货"},
            {"title": "如何申请退款", "source": "FAQ#5", "snippet": "进入订单详情页提交退款申请"},
        ],
        traceId="t1",
    )

    summary = summarize_tool_result(record)

    assert "检索到 2 条可引用政策资料" in summary
    assert "退货规则" in summary


def test_policy_tool_result_summary_handles_empty_results():
    record = ToolCallRecord(
        name="search_policy_kb",
        arguments={"keyword": "不存在的规则"},
        ok=True,
        result=[],
        traceId="t1",
    )

    assert summarize_tool_result(record) == "没有检索到可引用的政策资料"


if __name__ == "__main__":
    tests = [
        test_policy_kb_tool_registered,
        test_policy_intent_routes_to_policy_kb_only,
        test_policy_fallback_uses_policy_kb,
        test_policy_tool_result_summary_includes_titles,
        test_policy_tool_result_summary_handles_empty_results,
    ]
    for test in tests:
        test()
    print("phase10 policy rag checks passed")
