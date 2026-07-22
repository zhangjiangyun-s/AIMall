import asyncio
import sys
import types
from contextlib import asynccontextmanager


httpx_stub = types.ModuleType("httpx")


class AsyncClientStub:
    def __init__(self, *args, **kwargs):
        self.is_closed = False

    async def aclose(self):
        self.is_closed = True


httpx_stub.AsyncClient = AsyncClientStub
sys.modules.setdefault("httpx", httpx_stub)

from app.agent.react_agent import ReActAgent
from app.api.chat_api import build_chat_context
from app.memory.session_memory import SessionMemoryStore
from app.schemas.chat_schema import ChatRequest
from app.schemas.tool_schema import ToolCallRecord


def run(coro):
    return asyncio.run(coro)


class DeterministicSummarizer:
    async def summarize(self, previous_summary, turns):
        users = "、".join(turn["user"] for turn in turns)
        return f"{previous_summary}|{users}".strip("|"), True


def product_search_record() -> ToolCallRecord:
    return ToolCallRecord(
        name="search_products",
        arguments={"keyword": "轻薄本"},
        ok=True,
        result=[
            {"productId": 1103, "name": "轻羽 Air 14 轻薄本", "stock": 58},
            {"productId": 1104, "name": "轻羽 Air 16 大屏本", "stock": 37},
            {"productId": 1105, "name": "雷竞 G5 游戏本", "stock": 29},
        ],
        traceId="trace-products",
    )


def test_memory_keeps_recent_turns_and_product_entities():
    now = [1000.0]
    store = SessionMemoryStore(
        ttl_seconds=100,
        max_turns=2,
        summarizer=DeterministicSummarizer(),
        clock=lambda: now[0],
    )

    for index in range(3):
        run(
            store.append(
                session_id="session-a",
                auth_token="token-a",
                user_message=f"问题 {index}",
                assistant_answer=f"回答 {index}",
                intent="PRODUCT_QA",
                trace_id=f"trace-{index}",
                tool_calls=[product_search_record()] if index == 2 else [],
            )
        )

    context = run(store.get("session-a", "token-a"))

    assert context["turnCount"] == 2
    assert context["recentTurns"][0]["user"] == "问题 1"
    assert context["recentTurns"][1]["user"] == "问题 2"
    assert context["entities"][1]["entity_id"] == "1104"
    assert context["entities"][1]["ordinal"] == 2


def test_memory_isolated_by_token_and_session_and_expires():
    now = [1000.0]
    store = SessionMemoryStore(ttl_seconds=10, max_turns=6, clock=lambda: now[0])
    run(
        store.append(
            session_id="session-a",
            auth_token="token-a",
            user_message="查看我的订单",
            assistant_answer="订单待发货",
            intent="ORDER_QA",
            trace_id="trace-order",
            tool_calls=[],
        )
    )

    assert run(store.get("session-a", "token-a"))["turnCount"] == 1
    assert run(store.get("session-a", "token-b"))["turnCount"] == 0
    assert run(store.get("session-b", "token-a"))["turnCount"] == 0

    now[0] = 1011.0
    assert run(store.get("session-a", "token-a"))["turnCount"] == 0


def test_memory_isolated_by_tenant_and_user_and_records_context_metadata():
    store = SessionMemoryStore(ttl_seconds=100, max_turns=6)
    run(
        store.append(
            session_id="shared-session",
            auth_token="shared-token",
            tenant_id="tenant-a",
            user_id=101,
            user_message="hello",
            assistant_answer="world",
            intent="GENERAL_QA",
            trace_id="trace-context",
            tool_calls=[],
        )
    )

    assert run(
        store.get("shared-session", "shared-token", tenant_id="tenant-a", user_id=101)
    )["turnCount"] == 1
    assert run(
        store.get("shared-session", "shared-token", tenant_id="tenant-b", user_id=101)
    )["turnCount"] == 0
    assert run(
        store.get("shared-session", "shared-token", tenant_id="tenant-a", user_id=202)
    )["turnCount"] == 0

    record = next(iter(store._sessions.values()))
    assert record.schema_version == "1"
    assert record.tenant_id == "tenant-a"
    assert record.user_id == 101
    assert record.session_id == "shared-session"
    assert record.owner_fingerprint


def test_memory_clear_removes_current_login_session():
    store = SessionMemoryStore(ttl_seconds=100, max_turns=6)
    run(
        store.append(
            session_id="session-a",
            auth_token="token-a",
            user_message="你好",
            assistant_answer="你好",
            intent="GENERAL_QA",
            trace_id="trace-hello",
            tool_calls=[],
        )
    )

    assert run(store.clear("session-a", "token-a")) is True
    assert run(store.get("session-a", "token-a"))["turnCount"] == 0


def test_persistent_memory_is_shared_and_cleared_across_instances():
    class PersistentBackend:
        enabled = True

        def __init__(self, now):
            self.now = now
            self.values = {}

        @asynccontextmanager
        async def lock(self, _key, ttl_seconds=30):
            yield

        async def get_json(self, key):
            value = self.values.get(key)
            if not value or value[1] <= self.now[0]:
                return None
            return dict(value[0])

        async def set_json(self, key, value, ttl_seconds):
            self.values[key] = (dict(value), self.now[0] + ttl_seconds)

        async def delete(self, *keys):
            removed = 0
            for key in keys:
                if self.values.pop(key, None) is not None:
                    removed += 1
            return removed

    now = [1000.0]
    backend = PersistentBackend(now)
    first = SessionMemoryStore(ttl_seconds=10, backend=backend, clock=lambda: now[0])
    second = SessionMemoryStore(ttl_seconds=10, backend=backend, clock=lambda: now[0])

    run(
        first.append(
            session_id="shared-session",
            auth_token="token-a",
            tenant_id="tenant-a",
            user_id=101,
            user_message="show my order",
            assistant_answer="the order is waiting for shipment",
            intent="ORDER_QA",
            trace_id="trace-persistent-memory",
            tool_calls=[],
        )
    )

    shared = run(
        second.get(
            "shared-session", "token-a", tenant_id="tenant-a", user_id=101
        )
    )
    assert shared["turnCount"] == 1
    assert shared["recentTurns"][0]["traceId"] == "trace-persistent-memory"
    assert run(
        second.get(
            "shared-session", "token-a", tenant_id="tenant-b", user_id=101
        )
    )["turnCount"] == 0

    assert run(
        second.clear(
            "shared-session", "token-a", tenant_id="tenant-a", user_id=101
        )
    ) is True
    assert run(
        first.get(
            "shared-session", "token-a", tenant_id="tenant-a", user_id=101
        )
    )["turnCount"] == 0

    run(
        first.append(
            session_id="expiring-session",
            auth_token="token-a",
            tenant_id="tenant-a",
            user_id=101,
            user_message="hello",
            assistant_answer="hello",
            intent="GENERAL_QA",
            trace_id="trace-expiring-memory",
            tool_calls=[],
        )
    )
    now[0] += 11
    assert run(
        second.get(
            "expiring-session", "token-a", tenant_id="tenant-a", user_id=101
        )
    )["turnCount"] == 0


def test_memory_capacity_evicts_earliest_session():
    now = [1000.0]
    store = SessionMemoryStore(ttl_seconds=100, max_turns=6, max_sessions=2, clock=lambda: now[0])
    for session_id in ("session-a", "session-b"):
        run(
            store.append(
                session_id=session_id,
                auth_token="token-a",
                user_message="你好",
                assistant_answer="你好",
                intent="GENERAL_QA",
                trace_id=session_id,
                tool_calls=[],
            )
        )
        now[0] += 1

    run(
        store.append(
            session_id="session-c",
            auth_token="token-a",
            user_message="你好",
            assistant_answer="你好",
            intent="GENERAL_QA",
            trace_id="session-c",
            tool_calls=[],
        )
    )

    assert run(store.get("session-a", "token-a"))["turnCount"] == 0
    assert run(store.get("session-b", "token-a"))["turnCount"] == 1
    assert run(store.get("session-c", "token-a"))["turnCount"] == 1


def test_react_resolves_second_product_from_session_memory():
    agent = ReActAgent()
    memory = {
        "entities": [
            {"kind": "product", "entity_id": "1103", "label": "轻羽 Air 14", "ordinal": 1},
            {"kind": "product", "entity_id": "1104", "label": "轻羽 Air 16", "ordinal": 2},
        ],
        "recentTurns": [],
    }
    request = ChatRequest(message="第二个怎么样？", sessionId="session-a", pageContext={"pageType": "GENERAL"})

    action = agent._scripted_next_action(request, "PRODUCT_QA", [], memory)

    assert action is not None
    assert action["action"] == "get_product_detail"
    assert action["arguments"] == {"productId": 1104}


def test_react_does_not_treat_preference_number_as_product_ordinal():
    agent = ReActAgent()
    memory = {
        "entities": [
            {"kind": "product", "entity_id": "1103", "label": "轻羽 Air 14", "ordinal": 1},
            {"kind": "product", "entity_id": "1104", "label": "轻羽 Air 16", "ordinal": 2},
        ],
        "recentTurns": [],
    }
    request = ChatRequest(
        message="请记住我的第 2 条偏好：购买建议要简洁。",
        sessionId="session-a",
        pageContext={"pageType": "GENERAL"},
    )

    assert agent._resolve_memory_entity(request.message, memory) is None
    assert agent._scripted_next_action(request, "GENERAL_QA", [], memory) is None


def test_react_resolves_order_pronoun_but_requeries_tool():
    agent = ReActAgent()
    memory = {
        "entities": [
            {
                "kind": "order",
                "entity_id": "AIM20260714000000000002",
                "label": "AIM20260714000000000002",
                "ordinal": 1,
            }
        ],
        "recentTurns": [],
    }
    request = ChatRequest(message="它什么时候发货？", sessionId="session-a", pageContext={"pageType": "GENERAL"})

    action = agent._scripted_next_action(request, "ORDER_QA", [], memory)

    assert action is not None
    assert action["action"] == "get_my_order_detail"
    assert action["arguments"] == {"orderSn": "AIM20260714000000000002"}


def test_final_generation_context_contains_recent_memory():
    request = ChatRequest(message="第二个怎么样？", sessionId="session-a")
    memory = {
        "turnCount": 1,
        "recentTurns": [{"user": "推荐两款轻薄本", "assistant": "第一款和第二款如下"}],
        "entities": [{"kind": "product", "entity_id": "1104", "ordinal": 2}],
    }

    _, _, _, context = build_chat_context(request, "trace-memory", "PRODUCT_QA", [], [], memory)

    assert context["sessionMemory"]["turnCount"] == 1
    assert context["sessionMemory"]["entities"][0]["entity_id"] == "1104"
