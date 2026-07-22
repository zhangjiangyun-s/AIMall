import pytest

from app.evaluation.stage6_rag_benchmark import aggregate_all_modes, aggregate_mode_runs


def report(run_id, recall=0.9, mrr=0.85, citation=0.96, faithfulness=0.98):
    def metric(value):
        return {"count": 20, "value": value}

    return {
        "runId": run_id,
        "datasetVersion": "1.2.3",
        "summary": {
            "recallAtK": metric(recall),
            "mrr": metric(mrr),
            "citationAccuracy": metric(citation),
            "citationFaithfulness": metric(faithfulness),
        },
    }


def test_stage6_rag_benchmark_uses_worst_of_three_runs():
    result = aggregate_mode_runs("VECTOR", [
        report("run-1"),
        report("run-2", recall=0.81, mrr=0.76, citation=0.93, faithfulness=0.93),
        report("run-3", recall=0.88),
    ])

    assert result["runCount"] == 3
    assert result["worstCaseSummary"]["recallAtK"]["value"] == 0.81
    assert result["worstCaseSummary"]["citationFaithfulness"]["value"] == 0.93
    assert result["passed"] is True


def test_stage6_rag_benchmark_rejects_missing_run_and_blocks_failed_mode():
    with pytest.raises(ValueError, match="at least 3"):
        aggregate_mode_runs("DOC_ONLY", [report("one"), report("two")])

    modes = {
        "DOC_ONLY": [report("d1"), report("d2"), report("d3")],
        "HYBRID": [report("h1"), report("h2"), report("h3", recall=0.5)],
        "VECTOR": [report("v1"), report("v2"), report("v3")],
    }
    result = aggregate_all_modes(modes)

    assert result["passed"] is False
    assert result["modes"]["HYBRID"]["passed"] is False


def test_stage6_rag_benchmark_rejects_replayed_or_mixed_dataset_runs():
    with pytest.raises(ValueError, match="unique non-empty runId"):
        aggregate_mode_runs("DOC_ONLY", [report("same"), report("same"), report("same")])

    mixed = [report("one"), report("two"), report("three")]
    mixed[2]["datasetVersion"] = "different"
    with pytest.raises(ValueError, match="one non-empty datasetVersion"):
        aggregate_mode_runs("DOC_ONLY", mixed)

    modes = {
        "DOC_ONLY": [report("d1"), report("d2"), report("d3")],
        "HYBRID": [report("h1"), report("h2"), report("h3")],
        "VECTOR": [report("v1"), report("v2"), report("v3")],
    }
    for item in modes["VECTOR"]:
        item["datasetVersion"] = "different"
    with pytest.raises(ValueError, match="same datasetVersion"):
        aggregate_all_modes(modes)
