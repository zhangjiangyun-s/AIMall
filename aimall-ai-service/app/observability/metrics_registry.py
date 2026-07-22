from __future__ import annotations

import math
from collections import Counter
from threading import Lock
from typing import Any


class MetricsRegistry:
    """Process-local, privacy-safe metrics for Agent operational health."""

    def __init__(self, max_latency_samples: int = 10_000) -> None:
        self._max_latency_samples = max_latency_samples
        self._lock = Lock()
        self._counters: Counter[str] = Counter()
        self._latencies: dict[str, list[int]] = {}
        self._cost_usd = 0.0
        self._cost_by_model: dict[str, dict[str, float | int | bool]] = {}
        self._cost_by_purpose: dict[str, dict[str, float | int | bool]] = {}
        self._gauges: dict[str, float] = {}

    def increment(self, name: str, amount: int = 1) -> None:
        with self._lock:
            self._counters[name] += max(0, amount)

    def set_gauge(self, name: str, value: float | int) -> None:
        with self._lock:
            self._gauges[name] = float(value)

    def record_chat(self, trace: dict[str, Any]) -> None:
        latency = int(trace.get("latencyMs") or 0)
        degraded = bool(trace.get("degraded"))
        tool_calls = trace.get("toolCalls") if isinstance(trace.get("toolCalls"), list) else []
        retrieval_status = str(trace.get("retrievalStatus") or "")
        max_steps_reached = bool(trace.get("agentMaxStepsReached"))
        reflection = trace.get("reflection") if isinstance(trace.get("reflection"), dict) else {}
        rag_calls = [call for call in tool_calls if isinstance(call, dict) and call.get("name") == "search_policy_kb"]

        with self._lock:
            self._counters["chat.requests"] += 1
            self._counters["chat.degraded"] += int(degraded)
            self._counters["chat.refused"] += int(str(reflection.get("status") or "") == "REFUSED")
            self._counters["agent.max_steps_reached"] += int(max_steps_reached)
            self._counters["tool.calls"] += len(tool_calls)
            self._counters["tool.failures"] += sum(1 for call in tool_calls if isinstance(call, dict) and not call.get("ok"))
            self._counters["rag.queries"] += int(bool(retrieval_status))
            self._counters["rag.no_match"] += int(retrieval_status == "NO_MATCH")
            self._counters["rag.citation_errors"] += int(
                bool(rag_calls) and str(reflection.get("status") or "") in {"REFUSED", "DEGRADED"}
            )
            self._counters["reflection.retries"] += int(int(reflection.get("generationAttempts") or 0) > 1)
            self._counters["reflection.degraded"] += int(str(reflection.get("status") or "") == "DEGRADED")
            self._append_latency("chat", latency)
            for call in tool_calls:
                if isinstance(call, dict):
                    self._append_latency("tool", int(call.get("latencyMs") or 0))
            for call in rag_calls:
                self._append_latency("rag", int(call.get("latencyMs") or 0))
            self._append_latency("reflection", int(reflection.get("pipelineLatencyMs") or 0))

    def record_llm(
        self,
        *,
        latency_ms: int,
        input_tokens: int = 0,
        output_tokens: int = 0,
        cost_usd: float = 0.0,
        failed: bool = False,
        purpose: str = "GENERATION",
        model: str = "unknown",
        fallback_used: bool = False,
        usage_source: str = "UNKNOWN",
        pricing_configured: bool | None = None,
    ) -> None:
        purpose_key = self._label(purpose)
        model_key = self._label(model)
        normalized_usage_source = self._label(usage_source).lower()
        has_usage = not failed and (input_tokens > 0 or output_tokens > 0)
        priced = bool(cost_usd > 0) if pricing_configured is None else bool(pricing_configured)
        with self._lock:
            self._counters["llm.calls"] += 1
            self._counters["llm.failures"] += int(failed)
            self._counters["llm.input_tokens"] += max(0, input_tokens)
            self._counters["llm.output_tokens"] += max(0, output_tokens)
            self._counters[f"llm.purpose.{purpose_key}.calls"] += 1
            self._counters[f"llm.purpose.{purpose_key}.failures"] += int(failed)
            self._counters[f"llm.model.{model_key}.calls"] += 1
            self._counters[f"llm.model.{model_key}.failures"] += int(failed)
            self._counters["llm.fallback.calls"] += int(fallback_used)
            if has_usage:
                self._counters[f"llm.usage.{normalized_usage_source}.calls"] += 1
                self._counters[f"llm.pricing.{'priced' if priced else 'unpriced'}.calls"] += 1
                if not priced:
                    self._counters["llm.pricing.unpriced_input_tokens"] += max(0, input_tokens)
                    self._counters["llm.pricing.unpriced_output_tokens"] += max(0, output_tokens)
            self._cost_usd += max(0.0, cost_usd)
            if has_usage:
                self._record_cost_dimension(self._cost_by_model, model_key, input_tokens, output_tokens, cost_usd, priced)
                self._record_cost_dimension(self._cost_by_purpose, purpose_key, input_tokens, output_tokens, cost_usd, priced)
            self._append_latency("llm", latency_ms)

    def record_multi_agent_plan(self, plan: dict[str, Any] | None) -> None:
        if not isinstance(plan, dict):
            return
        delegations = plan.get("delegations") if isinstance(plan.get("delegations"), list) else []
        fallback = bool(plan.get("fallbackToLegacy"))
        with self._lock:
            self._counters["multi_agent.requests"] += 1
            self._counters["multi_agent.legacy_fallbacks"] += int(fallback)
            self._counters["multi_agent.delegations"] += len(delegations)
            self._counters["multi_agent.duplicate_calls_prevented"] += sum(
                int(item.get("duplicateCallsPrevented") or 0) for item in delegations if isinstance(item, dict)
            )
            self._counters["multi_agent.skipped"] += sum(
                1 for item in delegations if isinstance(item, dict) and item.get("status") == "SKIPPED"
            )
            self._counters["multi_agent.failures"] += sum(
                1 for item in delegations if isinstance(item, dict) and item.get("status") == "FAILED"
            )
            for item in delegations:
                if isinstance(item, dict) and item.get("specialist"):
                    self._counters[f"multi_agent.specialist.{self._label(str(item['specialist']))}.delegations"] += 1

    def snapshot(self) -> dict[str, Any]:
        with self._lock:
            counters = dict(self._counters)
            requests = counters.get("chat.requests", 0)
            return {
                "schemaVersion": "AIMALL_AGENT_METRICS_V1",
                "counters": counters,
                "rates": {
                    "degradedRate": self._rate(counters.get("chat.degraded", 0), requests),
                    "toolFailureRate": self._rate(counters.get("tool.failures", 0), counters.get("tool.calls", 0)),
                    "ragNoMatchRate": self._rate(counters.get("rag.no_match", 0), counters.get("rag.queries", 0)),
                    "llmFailureRate": self._rate(counters.get("llm.failures", 0), counters.get("llm.calls", 0)),
                    "reflectionRetryRate": self._rate(counters.get("reflection.retries", 0), requests),
                    "maxStepsReachedRate": self._rate(counters.get("agent.max_steps_reached", 0), requests),
                },
                "latencyMs": {name: self._latency_summary(values) for name, values in self._latencies.items()},
                "cost": {
                    "currency": "USD",
                    "estimatedCost": round(self._cost_usd, 8),
                    "inputTokens": counters.get("llm.input_tokens", 0),
                    "outputTokens": counters.get("llm.output_tokens", 0),
                    "pricingStatus": self._pricing_status(counters),
                    "usageSource": {
                        "providerCalls": counters.get("llm.usage.provider.calls", 0),
                        "estimatedCalls": counters.get("llm.usage.estimated.calls", 0),
                        "partialProviderCalls": counters.get("llm.usage.provider_partial.calls", 0),
                        "unknownCalls": counters.get("llm.usage.unknown.calls", 0),
                    },
                    "unpriced": {
                        "calls": counters.get("llm.pricing.unpriced.calls", 0),
                        "inputTokens": counters.get("llm.pricing.unpriced_input_tokens", 0),
                        "outputTokens": counters.get("llm.pricing.unpriced_output_tokens", 0),
                    },
                    "byModel": self._cost_dimensions(self._cost_by_model),
                    "byPurpose": self._cost_dimensions(self._cost_by_purpose),
                },
                "gauges": dict(self._gauges),
            }

    def reset(self) -> None:
        with self._lock:
            self._counters.clear()
            self._latencies.clear()
            self._cost_usd = 0.0
            self._cost_by_model.clear()
            self._cost_by_purpose.clear()
            self._gauges.clear()

    @staticmethod
    def _record_cost_dimension(
        target: dict[str, dict[str, float | int | bool]],
        key: str,
        input_tokens: int,
        output_tokens: int,
        cost_usd: float,
        priced: bool,
    ) -> None:
        item = target.setdefault(
            key,
            {"calls": 0, "inputTokens": 0, "outputTokens": 0, "estimatedCost": 0.0, "fullyPriced": True},
        )
        item["calls"] = int(item["calls"]) + 1
        item["inputTokens"] = int(item["inputTokens"]) + max(0, input_tokens)
        item["outputTokens"] = int(item["outputTokens"]) + max(0, output_tokens)
        item["estimatedCost"] = float(item["estimatedCost"]) + max(0.0, cost_usd)
        item["fullyPriced"] = bool(item["fullyPriced"]) and priced

    @staticmethod
    def _cost_dimensions(source: dict[str, dict[str, float | int | bool]]) -> dict[str, dict[str, float | int | bool]]:
        return {
            key: {**value, "estimatedCost": round(float(value["estimatedCost"]), 8)}
            for key, value in source.items()
        }

    @staticmethod
    def _pricing_status(counters: dict[str, int]) -> str:
        priced = counters.get("llm.pricing.priced.calls", 0)
        unpriced = counters.get("llm.pricing.unpriced.calls", 0)
        if priced and unpriced:
            return "PARTIAL"
        if unpriced:
            return "UNPRICED"
        if priced:
            return "PRICED"
        return "NO_USAGE"

    def health_summary(self, thresholds: dict[str, float | int]) -> dict[str, Any]:
        snapshot = self.snapshot()
        rates = snapshot["rates"]
        latency = snapshot["latencyMs"]
        rules = (
            ("LLM_FAILURE_RATE", "llmFailureRate", rates.get("llmFailureRate", 0.0), thresholds["llmFailureRate"], "RATE"),
            ("TOOL_FAILURE_RATE", "toolFailureRate", rates.get("toolFailureRate", 0.0), thresholds["toolFailureRate"], "RATE"),
            ("DEGRADED_RATE", "degradedRate", rates.get("degradedRate", 0.0), thresholds["degradedRate"], "RATE"),
            ("RAG_NO_MATCH_RATE", "ragNoMatchRate", rates.get("ragNoMatchRate", 0.0), thresholds["ragNoMatchRate"], "RATE"),
            ("LLM_P95_LATENCY", "llm.p95", latency.get("llm", {}).get("p95", 0.0), thresholds["llmP95Ms"], "MS"),
            ("RAG_P95_LATENCY", "rag.p95", latency.get("rag", {}).get("p95", 0.0), thresholds["ragP95Ms"], "MS"),
        )
        alerts = [
            {"id": rule_id, "metric": metric, "actual": actual, "threshold": threshold, "unit": unit, "severity": "WARNING"}
            for rule_id, metric, actual, threshold, unit in rules
            if actual > threshold
        ]
        return {
            "status": "DEGRADED" if alerts else "UP",
            "alerts": alerts,
            "thresholds": thresholds,
            "metrics": snapshot,
        }

    def _append_latency(self, name: str, value: int) -> None:
        if value < 0:
            return
        samples = self._latencies.setdefault(name, [])
        samples.append(value)
        if len(samples) > self._max_latency_samples:
            del samples[: len(samples) - self._max_latency_samples]

    def _latency_summary(self, values: list[int]) -> dict[str, float | int]:
        if not values:
            return {"count": 0, "avg": 0.0, "p50": 0.0, "p95": 0.0, "p99": 0.0}
        ordered = sorted(values)
        return {
            "count": len(ordered),
            "avg": round(sum(ordered) / len(ordered), 2),
            "p50": self._percentile(ordered, 0.50),
            "p95": self._percentile(ordered, 0.95),
            "p99": self._percentile(ordered, 0.99),
        }

    @staticmethod
    def _percentile(values: list[int], percentile: float) -> float:
        index = max(0, math.ceil(len(values) * percentile) - 1)
        return float(values[index])

    @staticmethod
    def _rate(numerator: int, denominator: int) -> float:
        return round(numerator / denominator, 4) if denominator else 0.0

    @staticmethod
    def _label(value: str) -> str:
        return "".join(char if char.isalnum() or char in {"-", "_", "."} else "_" for char in str(value or "unknown"))[:120]


agent_metrics = MetricsRegistry()
