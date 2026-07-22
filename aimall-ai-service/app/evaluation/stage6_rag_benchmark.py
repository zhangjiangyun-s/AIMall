from __future__ import annotations

from typing import Any

from app.config.settings import normalize_rag_retrieval_mode
from app.evaluation.rag_mode_quality_gate import RagModeQualityGate, rag_mode_quality_gate


CANONICAL_MODES = ("DOC_ONLY", "HYBRID", "VECTOR")
LOWER_IS_WORSE = ("recallAtK", "mrr", "citationAccuracy", "citationFaithfulness")


def aggregate_mode_runs(
    retrieval_mode: str,
    reports: list[dict[str, Any]],
    *,
    minimum_runs: int = 3,
    quality_gate: RagModeQualityGate = rag_mode_quality_gate,
) -> dict[str, Any]:
    mode = normalize_rag_retrieval_mode(retrieval_mode)
    if mode not in CANONICAL_MODES:
        raise ValueError(f"Unsupported RAG retrieval mode: {mode}")
    if len(reports) < minimum_runs:
        raise ValueError(f"{mode} requires at least {minimum_runs} independent runs")

    run_ids = [str(report.get("runId") or "").strip() for report in reports]
    if any(not run_id for run_id in run_ids) or len(set(run_ids)) != len(run_ids):
        raise ValueError(f"{mode} requires unique non-empty runId values")
    dataset_versions = {str(report.get("datasetVersion") or "").strip() for report in reports}
    if "" in dataset_versions or len(dataset_versions) != 1:
        raise ValueError(f"{mode} runs must use one non-empty datasetVersion")

    summaries = [report.get("summary") for report in reports]
    if not all(isinstance(summary, dict) for summary in summaries):
        raise ValueError(f"{mode} report is missing summary")

    worst_summary: dict[str, dict[str, Any]] = {}
    for metric in LOWER_IS_WORSE:
        values = [_metric(summary, metric) for summary in summaries]
        worst_summary[metric] = {
            "count": min(value[0] for value in values),
            "value": min(value[1] for value in values),
        }

    gate = quality_gate.evaluate(mode, worst_summary)
    return {
        "retrievalMode": mode,
        "runCount": len(reports),
        "runIds": run_ids,
        "datasetVersion": next(iter(dataset_versions)),
        "worstCaseSummary": worst_summary,
        "qualityGate": gate,
        "passed": gate["passed"],
    }


def aggregate_all_modes(
    reports_by_mode: dict[str, list[dict[str, Any]]],
    *,
    minimum_runs: int = 3,
    quality_gate: RagModeQualityGate = rag_mode_quality_gate,
) -> dict[str, Any]:
    modes = {
        mode: aggregate_mode_runs(
            mode,
            reports_by_mode.get(mode, []),
            minimum_runs=minimum_runs,
            quality_gate=quality_gate,
        )
        for mode in CANONICAL_MODES
    }
    dataset_versions = {result["datasetVersion"] for result in modes.values()}
    if len(dataset_versions) != 1:
        raise ValueError("All RAG modes must use the same datasetVersion")
    return {
        "schemaVersion": "AIMALL_STAGE6_RAG_BENCHMARK_V1",
        "minimumRunsPerMode": minimum_runs,
        "datasetVersion": next(iter(dataset_versions)),
        "modes": modes,
        "passed": all(result["passed"] for result in modes.values()),
    }


def _metric(summary: dict[str, Any], metric: str) -> tuple[int, float]:
    raw = summary.get(metric)
    if not isinstance(raw, dict) or not isinstance(raw.get("value"), (int, float)):
        raise ValueError(f"RAG report is missing numeric metric {metric}")
    count = int(raw.get("count") or 0)
    if count <= 0:
        raise ValueError(f"RAG report metric {metric} has no evaluated samples")
    return count, float(raw["value"])
