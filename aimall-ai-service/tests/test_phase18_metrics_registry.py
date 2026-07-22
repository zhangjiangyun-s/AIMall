import asyncio

from app.observability.metrics_registry import MetricsRegistry
from app.observability.trace_logger import AgentTraceLogger


def test_metrics_aggregates_chat_tool_rag_and_latency_percentiles():
    registry = MetricsRegistry()
    registry.record_chat(
        {
            "latencyMs": 100,
            "degraded": False,
            "retrievalStatus": "OK",
            "reflection": {"status": "PASSED", "pipelineLatencyMs": 25, "generationAttempts": 2},
            "toolCalls": [
                {"name": "search_policy_kb", "ok": True, "latencyMs": 10},
                {"name": "get_product_detail", "ok": False, "latencyMs": 30},
            ],
        }
    )
    registry.record_chat(
        {
            "latencyMs": 300,
            "degraded": True,
            "retrievalStatus": "NO_MATCH",
            "reflection": {"status": "REFUSED"},
            "toolCalls": [],
        }
    )

    snapshot = registry.snapshot()

    assert snapshot["counters"]["chat.requests"] == 2
    assert snapshot["counters"]["tool.failures"] == 1
    assert snapshot["rates"]["degradedRate"] == 0.5
    assert snapshot["rates"]["ragNoMatchRate"] == 0.5
    assert snapshot["rates"]["reflectionRetryRate"] == 0.5
    assert snapshot["latencyMs"]["chat"] == {"count": 2, "avg": 200.0, "p50": 100.0, "p95": 300.0, "p99": 300.0}
    assert snapshot["latencyMs"]["rag"]["p95"] == 10.0
    assert snapshot["latencyMs"]["reflection"]["p95"] == 25.0


def test_metrics_records_llm_usage_without_raw_prompt_data():
    registry = MetricsRegistry()
    registry.record_llm(latency_ms=40, input_tokens=120, output_tokens=30, cost_usd=0.0012)

    snapshot = registry.snapshot()

    assert snapshot["counters"]["llm.calls"] == 1
    assert snapshot["cost"]["currency"] == "USD"
    assert snapshot["cost"]["estimatedCost"] == 0.0012
    assert snapshot["cost"]["inputTokens"] == 120
    assert snapshot["cost"]["outputTokens"] == 30
    assert snapshot["cost"]["pricingStatus"] == "PRICED"


def test_metrics_distinguishes_unpriced_usage_from_free_calls():
    registry = MetricsRegistry()
    registry.record_llm(
        latency_ms=40,
        input_tokens=120,
        output_tokens=30,
        model="agnes-test",
        purpose="GENERATION",
        usage_source="PROVIDER",
        pricing_configured=False,
    )

    cost = registry.snapshot()["cost"]

    assert cost["estimatedCost"] == 0
    assert cost["pricingStatus"] == "UNPRICED"
    assert cost["usageSource"]["providerCalls"] == 1
    assert cost["unpriced"] == {"calls": 1, "inputTokens": 120, "outputTokens": 30}
    assert cost["byModel"]["agnes-test"]["fullyPriced"] is False
    assert cost["byPurpose"]["GENERATION"]["inputTokens"] == 120


def test_health_summary_exposes_threshold_alerts_without_trace_data():
    registry = MetricsRegistry()
    registry.record_llm(latency_ms=120, failed=True)

    health = registry.health_summary(
        {
            "llmFailureRate": 0.05,
            "toolFailureRate": 1.0,
            "degradedRate": 1.0,
            "ragNoMatchRate": 1.0,
            "llmP95Ms": 1000,
            "ragP95Ms": 1000,
        }
    )

    assert health["status"] == "DEGRADED"
    assert health["alerts"][0]["id"] == "LLM_FAILURE_RATE"
    assert "traceId" not in str(health)


def test_metrics_counts_max_step_exhaustion_separately_from_tool_failures():
    registry = MetricsRegistry()
    registry.record_chat({"latencyMs": 1, "agentMaxStepsReached": True, "toolCalls": []})

    snapshot = registry.snapshot()

    assert snapshot["counters"]["agent.max_steps_reached"] == 1
    assert snapshot["rates"]["maxStepsReachedRate"] == 1.0


def test_metrics_snapshot_logger_persists_aggregate_without_user_fields(tmp_path):
    registry = MetricsRegistry()
    registry.record_llm(latency_ms=20, input_tokens=10, output_tokens=5)
    logger = AgentTraceLogger(str(tmp_path / "metrics"))

    asyncio.run(logger.write(registry.snapshot()))
    content = next((tmp_path / "metrics").glob("*.jsonl")).read_text(encoding="utf-8")

    assert "AIMALL_AGENT_METRICS_V1" in content
    assert "inputTokens" in content
    assert "message" not in content.lower()


def test_metrics_snapshot_schema_excludes_user_content():
    summary = MetricsRegistry().snapshot()

    assert summary["schemaVersion"] == "AIMALL_AGENT_METRICS_V1"
    assert "message" not in str(summary).lower()


def test_metrics_records_multi_agent_plan_without_user_content():
    registry = MetricsRegistry()
    registry.record_multi_agent_plan(
        {
            "fallbackToLegacy": False,
            "delegations": [
                {"specialist": "PRODUCT_SPECIALIST", "status": "COMPLETED"},
                {"specialist": "POLICY_SPECIALIST", "status": "FAILED", "duplicateCallsPrevented": 2},
            ],
        }
    )

    counters = registry.snapshot()["counters"]

    assert counters["multi_agent.requests"] == 1
    assert counters["multi_agent.delegations"] == 2
    assert counters["multi_agent.failures"] == 1
    assert counters["multi_agent.duplicate_calls_prevented"] == 2
    assert counters["multi_agent.specialist.PRODUCT_SPECIALIST.delegations"] == 1
