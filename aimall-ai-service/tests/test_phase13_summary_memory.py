import asyncio
from contextlib import asynccontextmanager

from app.api.chat_api import build_chat_context
from app.memory.session_memory import SessionMemoryStore
from app.memory.summarizer import SESSION_SUMMARY_VERSION, SessionSummarizer
from app.schemas.chat_schema import ChatRequest
from app.schemas.tool_schema import ToolCallRecord


def run(coro):
    return asyncio.run(coro)


class RecordingSummarizer:
    def __init__(self):
        self.calls = []

    async def summarize(self, previous_summary, turns):
        self.calls.append((previous_summary, turns))
        users = "、".join(turn["user"] for turn in turns)
        return f"{previous_summary}|{users}".strip("|"), False


def append_turn(store, index, tool_calls=None):
    return run(
        store.append(
            session_id="phase13-session",
            auth_token="phase13-token",
            user_message=f"问题 {index}",
            assistant_answer=f"回答 {index}",
            intent="PRODUCT_QA",
            trace_id=f"trace-{index}",
            tool_calls=tool_calls or [],
        )
    )


def product_record(product_id=1103):
    return ToolCallRecord(
        name="search_products",
        arguments={"keyword": "轻薄本"},
        ok=True,
        result=[{"productId": product_id, "name": "轻羽 Air 14", "stock": 58}],
        traceId="trace-product",
    )


def test_seventh_turn_creates_summary_and_keeps_six_raw_turns():
    summarizer = RecordingSummarizer()
    store = SessionMemoryStore(max_turns=6, summarizer=summarizer)

    for index in range(1, 8):
        context = append_turn(store, index)

    assert context["turnCount"] == 6
    assert context["totalTurnCount"] == 7
    assert context["recentTurns"][0]["user"] == "问题 2"
    assert context["recentTurns"][-1]["user"] == "问题 7"
    assert context["summary"] == "问题 1"
    assert context["summaryVersion"] == SESSION_SUMMARY_VERSION
    assert context["compressionCount"] == 1
    assert len(summarizer.calls) == 1


def test_summary_rolls_forward_instead_of_overwriting_previous_summary():
    summarizer = RecordingSummarizer()
    store = SessionMemoryStore(max_turns=2, summarizer=summarizer)

    for index in range(1, 5):
        context = append_turn(store, index)

    assert context["summary"] == "问题 1|问题 2"
    assert context["totalTurnCount"] == 4
    assert context["compressionCount"] == 2
    assert summarizer.calls[1][0] == "问题 1"


def test_entities_from_compressed_turns_remain_available():
    summarizer = RecordingSummarizer()
    store = SessionMemoryStore(max_turns=2, summarizer=summarizer)

    append_turn(store, 1, [product_record(1103)])
    append_turn(store, 2)
    context = append_turn(store, 3)

    assert context["recentTurns"][0]["user"] == "问题 2"
    assert any(entity["entity_id"] == "1103" for entity in context["entities"])


def test_context_is_bounded_by_token_budget():
    summarizer = RecordingSummarizer()
    store = SessionMemoryStore(max_turns=2, context_token_budget=180, summarizer=summarizer)

    for index in range(1, 5):
        run(
            store.append(
                session_id="phase13-session",
                auth_token="phase13-token",
                user_message=f"问题 {index}" + "很长的上下文" * 30,
                assistant_answer=f"回答 {index}" + "很长的回答" * 40,
                intent="PRODUCT_QA",
                trace_id=f"trace-{index}",
                tool_calls=[],
            )
        )
    context = run(store.get("phase13-session", "phase13-token"))

    assert context["estimatedTokens"] <= 180
    assert context["truncated"] is True


def test_summarizer_uses_deterministic_fallback_when_model_fails(monkeypatch):
    from app.memory import summarizer as summarizer_module

    async def fail_chat(*args, **kwargs):
        raise TimeoutError("summary timeout")

    monkeypatch.setattr(summarizer_module.agnes_client, "api_key", "test-key")
    monkeypatch.setattr(summarizer_module.agnes_client, "base_url", "http://invalid.local")
    monkeypatch.setattr(summarizer_module.agnes_client, "model", "test-model")
    monkeypatch.setattr(summarizer_module.agnes_client, "chat", fail_chat)

    summary, fallback_used = run(
        SessionSummarizer().summarize(
            "之前用户预算 3000 元",
            [{"user": "想要轻薄本", "assistant": "已经搜索商品", "intent": "PRODUCT_QA", "toolSummaries": []}],
        )
    )

    assert fallback_used is True
    assert "之前用户预算 3000 元" in summary
    assert "想要轻薄本" in summary


def test_final_generation_context_contains_summary_metadata():
    request = ChatRequest(message="接着说", sessionId="phase13-session")
    memory = {
        "summary": "用户预算 3000 元，需要轻薄本。",
        "summaryVersion": SESSION_SUMMARY_VERSION,
        "recentTurnCount": 6,
        "totalTurnCount": 9,
        "estimatedTokens": 320,
        "recentTurns": [],
        "entities": [],
    }

    _, _, _, context = build_chat_context(request, "trace-phase13", "PRODUCT_QA", [], [], memory)

    assert context["sessionMemory"]["summary"] == "用户预算 3000 元，需要轻薄本。"
    assert context["sessionMemory"]["totalTurnCount"] == 9


def test_persistent_summary_rolls_forward_across_store_instances():
    class PersistentBackend:
        enabled = True

        def __init__(self):
            self.values = {}

        @asynccontextmanager
        async def lock(self, _key, ttl_seconds=30):
            yield

        async def get_json(self, key):
            value = self.values.get(key)
            return dict(value[0]) if value else None

        async def set_json(self, key, value, ttl_seconds):
            self.values[key] = (dict(value), ttl_seconds)

        async def delete(self, *keys):
            removed = 0
            for key in keys:
                if self.values.pop(key, None) is not None:
                    removed += 1
            return removed

    backend = PersistentBackend()
    summarizer = RecordingSummarizer()
    first = SessionMemoryStore(max_turns=2, summarizer=summarizer, backend=backend)
    second = SessionMemoryStore(max_turns=2, summarizer=summarizer, backend=backend)

    append_turn(first, 1, [product_record(1103)])
    append_turn(first, 2)
    first_context = append_turn(first, 3)

    assert first_context["summary"] == "问题 1"
    assert first_context["compressionCount"] == 1

    loaded = run(second.get("phase13-session", "phase13-token"))
    assert loaded["summary"] == "问题 1"
    assert loaded["compressionCount"] == 1
    assert any(entity["entity_id"] == "1103" for entity in loaded["entities"])

    second_context = append_turn(second, 4)
    assert second_context["summary"] == "问题 1|问题 2"
    assert second_context["totalTurnCount"] == 4
    assert second_context["compressionCount"] == 2

    reloaded = run(first.get("phase13-session", "phase13-token"))
    assert reloaded["summary"] == "问题 1|问题 2"
    assert reloaded["compressionCount"] == 2
    assert any(entity["entity_id"] == "1103" for entity in reloaded["entities"])
