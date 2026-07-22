import asyncio
import json
from pathlib import Path

import pytest

from app.evaluation import (
    EvaluationRunner,
    EvaluationRunnerConfig,
    TransportResponse,
    load_evaluation_dataset,
    parse_sse,
)


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "data" / "evaluation" / "manifest.json"


class FakeTransport:
    def __init__(self, delay=0):
        self.delay = delay
        self.logins = []
        self.sessions = []
        self.chats = []
        self.closed = False
        self.active = 0
        self.max_active = 0

    async def login(self, username, password):
        self.logins.append((username, password))
        return f"token-{username}"

    async def clear_session(self, session_id, token):
        self.sessions.append((session_id, token))

    async def chat(self, *, message, session_id, trace_id, page_context, token):
        self.active += 1
        self.max_active = max(self.max_active, self.active)
        try:
            if self.delay:
                await asyncio.sleep(self.delay)
            self.chats.append(
                {
                    "message": message,
                    "sessionId": session_id,
                    "traceId": trace_id,
                    "pageContext": page_context,
                    "token": token,
                }
            )
            events = [
                {"type": "delta", "content": "测试回答"},
                {
                    "type": "done",
                    "traceId": trace_id,
                    "intent": "PRODUCT_QA",
                    "toolCalls": [],
                    "memoryEntities": [],
                },
            ]
            raw = "".join(f"data: {json.dumps(item, ensure_ascii=False)}\n\n" for item in events)
            return TransportResponse(status_code=200, raw_text=raw, events=events)
        finally:
            self.active -= 1

    async def close(self):
        self.closed = True


def config(tmp_path, **updates):
    values = {
        "password": "test-password",
        "timeout_seconds": 2,
        "concurrency": 2,
        "output_path": tmp_path / "result.json",
    }
    values.update(updates)
    return EvaluationRunnerConfig(**values)


def test_parse_sse_supports_multiple_data_events():
    raw = 'data: {"type":"delta","content":"a"}\r\n\r\ndata: {"type":"done"}\r\n\r\n'
    assert [item["type"] for item in parse_sse(raw)] == ["delta", "done"]


def test_runner_uses_isolated_sessions_and_does_not_persist_tokens(tmp_path):
    dataset = load_evaluation_dataset(MANIFEST)
    transport = FakeTransport()
    runner = EvaluationRunner(config(tmp_path), transport)
    report = asyncio.run(
        runner.run(dataset, case_ids={"AIM-EVAL-ORDER-001", "AIM-EVAL-ORDER-002"})
    )

    assert report["counts"]["SUCCESS"] == 2
    assert len({item["sessionId"] for item in report["results"]}) == 2
    assert len(transport.sessions) == 4
    assert transport.logins == [("phase11_user_a", "test-password")]
    serialized = (tmp_path / "result.json").read_text(encoding="utf-8")
    assert "token-phase11_user_a" not in serialized
    assert "test-password" not in serialized
    assert transport.closed is True


def test_runner_replays_only_user_history_turns(tmp_path):
    dataset = load_evaluation_dataset(MANIFEST)
    transport = FakeTransport()
    runner = EvaluationRunner(config(tmp_path), transport)
    report = asyncio.run(runner.run(dataset, case_ids={"AIM-EVAL-MEMORY-001"}))

    assert report["counts"]["SUCCESS"] == 1
    assert [item["message"] for item in transport.chats] == [
        "帮我推荐两台轻薄本",
        "那第二个呢？",
    ]
    assert len(report["results"][0]["historyRuns"]) == 1


def test_anonymous_case_uses_unique_session_without_protected_clear_call(tmp_path):
    dataset = load_evaluation_dataset(MANIFEST)
    source = next(case for case in dataset.cases if case.id == "AIM-EVAL-PRODUCT-001")
    anonymous_fixture = source.fixture.model_copy(
        update={"authRequired": False, "userFixture": None}
    )
    anonymous_case = source.model_copy(update={"fixture": anonymous_fixture})
    dataset = dataset.model_copy(update={"cases": [anonymous_case]})
    transport = FakeTransport()
    runner = EvaluationRunner(config(tmp_path), transport)
    report = asyncio.run(runner.run(dataset, case_ids={"AIM-EVAL-PRODUCT-001"}))

    assert report["counts"]["SUCCESS"] == 1
    assert transport.sessions == []
    assert transport.chats[0]["token"] is None


def test_runner_respects_concurrency_limit(tmp_path):
    dataset = load_evaluation_dataset(MANIFEST)
    transport = FakeTransport(delay=0.02)
    runner = EvaluationRunner(config(tmp_path, concurrency=2), transport)
    asyncio.run(runner.run(dataset, limit=4))

    assert transport.max_active == 2


def test_fault_injection_case_is_explicitly_unsupported(tmp_path):
    dataset = load_evaluation_dataset(MANIFEST)
    transport = FakeTransport()
    runner = EvaluationRunner(config(tmp_path, enable_fault_harness=False), transport)
    report = asyncio.run(
        runner.run(dataset, case_ids={"AIM-EVAL-TOOL_FAILURE-001"})
    )

    assert report["counts"]["UNSUPPORTED"] == 1
    assert report["results"][0]["errorType"] == "FAULT_INJECTION_NOT_CONFIGURED"
    assert transport.chats == []


def test_isolated_fault_harness_produces_terminal_result_without_http_calls(tmp_path):
    dataset = load_evaluation_dataset(MANIFEST)
    transport = FakeTransport()
    runner = EvaluationRunner(config(tmp_path), transport)
    report = asyncio.run(
        runner.run(
            dataset,
            case_ids={"AIM-EVAL-TOOL_FAILURE-001", "AIM-EVAL-TOOL_FAILURE-002"},
        )
    )

    assert report["counts"]["SUCCESS"] == 2
    assert transport.chats == []
    assert transport.logins == []
    for item in report["results"]:
        assert item["executionMode"] == "ISOLATED_FAULT_HARNESS_V1"
        assert item["done"]["faultInjection"]["isolated"] is True
        assert item["done"]["reflection"]["terminal"] is True
        assert item["done"]["toolCalls"][0]["ok"] is False


def test_timeout_is_recorded_and_report_is_written(tmp_path):
    dataset = load_evaluation_dataset(MANIFEST)
    transport = FakeTransport(delay=0.05)
    runner = EvaluationRunner(config(tmp_path, timeout_seconds=0.01), transport)
    report = asyncio.run(runner.run(dataset, case_ids={"AIM-EVAL-PRODUCT-001"}))

    assert report["counts"]["TIMEOUT"] == 1
    assert (tmp_path / "result.json").exists()


def test_resume_skips_success_and_retries_errors(tmp_path):
    dataset = load_evaluation_dataset(MANIFEST)
    output = tmp_path / "result.json"
    first_transport = FakeTransport()
    first = EvaluationRunner(config(tmp_path, output_path=output), first_transport)
    asyncio.run(first.run(dataset, case_ids={"AIM-EVAL-PRODUCT-001"}))

    second_transport = FakeTransport()
    second = EvaluationRunner(config(tmp_path, output_path=output, resume=True), second_transport)
    report = asyncio.run(second.run(dataset, case_ids={"AIM-EVAL-PRODUCT-001"}))

    assert report["counts"]["SUCCESS"] == 1
    assert second_transport.chats == []


def test_runner_rejects_unknown_case_or_category(tmp_path):
    dataset = load_evaluation_dataset(MANIFEST)

    async def run_unknown_case():
        runner = EvaluationRunner(config(tmp_path), FakeTransport())
        await runner.run(dataset, case_ids={"AIM-EVAL-NOT-FOUND-999"})

    async def run_unknown_category():
        runner = EvaluationRunner(config(tmp_path), FakeTransport())
        await runner.run(dataset, categories={"NOT_A_CATEGORY"})

    with pytest.raises(ValueError, match="unknown evaluation case ids"):
        asyncio.run(run_unknown_case())
    with pytest.raises(ValueError, match="unknown evaluation categories"):
        asyncio.run(run_unknown_category())
