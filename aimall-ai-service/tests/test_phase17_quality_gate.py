import copy

import pytest

from app.evaluation import (
    load_evaluation_dataset,
    load_quality_gate_config,
    quality_gate_evaluator,
)


MANIFEST = "data/evaluation/manifest.json"
CONFIG = "data/evaluation/quality-gates-v1.json"
SPECIALTY = {"GUARDRAIL", "PERMISSION", "HITL", "MEMORY", "TOOL_FAILURE", "REFLECTION"}


def passing_bundle():
    dataset = load_evaluation_dataset(MANIFEST)
    run_id = "EVAL_GATE_PASS"
    run = {
        "runId": run_id,
        "datasetId": dataset.manifest.datasetId,
        "datasetVersion": dataset.manifest.version,
        "results": [{"caseId": case.id, "status": "SUCCESS"} for case in dataset.cases],
    }
    deterministic = {
        "runId": run_id,
        "datasetId": dataset.manifest.datasetId,
        "datasetVersion": dataset.manifest.version,
        "summary": {
            "totalResults": 20,
            "deterministicPassRate": {"value": 1.0},
            "intentAccuracy": {"value": 1.0},
            "toolPrecision": {"value": 1.0},
            "toolRecall": {"value": 1.0},
            "taskCompletionRate": {"value": 1.0},
        },
        "caseScores": [
            {"caseId": case.id, "deterministicPassed": True, "failureCodes": []}
            for case in dataset.cases
        ],
    }
    rag_cases = [case for case in dataset.cases if case.expected.rag is not None]
    rag = {
        "runId": run_id,
        "datasetId": dataset.manifest.datasetId,
        "datasetVersion": dataset.manifest.version,
        "summary": {
            "totalRagResults": len(rag_cases),
            "passRate": {"value": 1.0},
            "recallAtK": {"value": 1.0},
            "mrr": {"value": 1.0},
            "ndcg": {"value": 1.0},
            "citationAccuracy": {"value": 1.0},
            "citationFaithfulness": {"value": 1.0},
            "noMatchAccuracy": {"value": 1.0},
        },
        "caseScores": [
            {"caseId": case.id, "passed": True, "failureCodes": []} for case in rag_cases
        ],
    }
    specialty_cases = [case for case in dataset.cases if case.category.value in SPECIALTY]
    specialty = {
        "runId": run_id,
        "datasetId": dataset.manifest.datasetId,
        "datasetVersion": dataset.manifest.version,
        "summary": {
            "totalSpecialtyResults": len(specialty_cases),
            "specialtyPassRate": {"value": 1.0},
            "guardrailAttackBlockRate": {"value": 1.0},
            "guardrailFalsePositiveRate": {"value": 0.0},
            "permissionBoundaryRate": {"value": 1.0},
            "hitlPendingSafetyRate": {"value": 1.0},
            "memoryCorrectnessRate": {"value": 1.0},
            "toolFailureSafeHandlingRate": {"value": 1.0},
            "reflectionComplianceRate": {"value": 1.0},
            "criticalCasePassRate": {"value": 1.0},
        },
        "caseScores": [
            {"caseId": case.id, "passed": True, "failureCodes": []}
            for case in specialty_cases
        ],
    }
    return dataset, run, deterministic, rag, specialty


def evaluate(bundle, baseline=None):
    dataset, run, deterministic, rag, specialty = bundle
    return quality_gate_evaluator.evaluate(
        dataset=dataset,
        run=run,
        deterministic=deterministic,
        rag=rag,
        specialty=specialty,
        config=load_quality_gate_config(CONFIG),
        baseline=baseline,
    )


def test_all_green_release_bundle_passes():
    report = evaluate(passing_bundle())

    assert report["status"] == "PASSED"
    assert report["summary"]["failedGates"] == 0
    assert report["summary"]["passedCases"] == 20
    assert report["blockingReasons"] == []


def test_incomplete_coverage_fails_closed():
    bundle = passing_bundle()
    bundle[1]["results"] = bundle[1]["results"][:12]
    bundle[2]["summary"]["totalResults"] = 12
    bundle[2]["caseScores"] = bundle[2]["caseScores"][:12]
    report = evaluate(bundle)

    assert report["status"] == "FAILED"
    assert "GATE_FAILED:coverage-run" in report["blockingReasons"]
    assert report["summary"]["notEvaluatedCases"] > 0


def test_current_critical_failure_blocks_even_when_aggregate_metrics_are_green():
    bundle = passing_bundle()
    case_id = "AIM-EVAL-HITL-001"
    score = next(item for item in bundle[4]["caseScores"] if item["caseId"] == case_id)
    score.update({"passed": False, "failureCodes": ["hitl_pending_created"]})
    report = evaluate(bundle)

    assert report["status"] == "FAILED"
    assert f"CRITICAL_CASE_FAILED:{case_id}" in report["blockingReasons"]


def test_case_and_metric_regressions_are_detected_against_baseline():
    baseline = evaluate(passing_bundle())
    current = passing_bundle()
    current[1]["runId"] = "EVAL_GATE_REGRESSED"
    current[2]["runId"] = "EVAL_GATE_REGRESSED"
    current[3]["runId"] = "EVAL_GATE_REGRESSED"
    current[4]["runId"] = "EVAL_GATE_REGRESSED"
    current[2]["summary"]["intentAccuracy"]["value"] = 0.8
    case_id = "AIM-EVAL-ORDER-001"
    case = next(item for item in current[2]["caseScores"] if item["caseId"] == case_id)
    case.update({"deterministicPassed": False, "failureCodes": ["intent"]})
    report = evaluate(current, baseline=baseline)

    assert report["status"] == "FAILED"
    assert any(item["caseId"] == case_id for item in report["regressions"]["caseRegressions"])
    assert any(item["gateId"] == "intent-accuracy" for item in report["regressions"]["metricRegressions"])
    assert "CASE_REGRESSION_LIMIT_EXCEEDED" in report["blockingReasons"]
    assert "METRIC_REGRESSION_DETECTED" in report["blockingReasons"]


def test_lte_false_positive_gate_fails_when_value_is_too_high():
    bundle = passing_bundle()
    bundle[4]["summary"]["guardrailFalsePositiveRate"]["value"] = 0.1
    report = evaluate(bundle)
    gate = next(item for item in report["gateResults"] if item["gateId"] == "guardrail-false-positive")

    assert gate["passed"] is False
    assert gate["operator"] == "LTE"


def test_bundle_run_id_mismatch_is_rejected():
    bundle = passing_bundle()
    bundle[3]["runId"] = "OTHER_RUN"
    with pytest.raises(ValueError, match="runId"):
        evaluate(bundle)


def test_markdown_contains_gate_and_failed_case_details():
    bundle = passing_bundle()
    case_id = "AIM-EVAL-HITL-001"
    score = next(item for item in bundle[4]["caseScores"] if item["caseId"] == case_id)
    score.update({"passed": False, "failureCodes": ["hitl_pending_created"]})
    markdown = quality_gate_evaluator.render_markdown(evaluate(bundle))

    assert "# AIMall Agent Quality Gate Report" in markdown
    assert "Gate Results" in markdown
    assert case_id in markdown
    assert "hitl_pending_created" in markdown


def test_quality_gate_config_rejects_duplicate_ids():
    config = load_quality_gate_config(CONFIG)
    payload = config.model_dump(mode="json")
    payload["gates"].append(copy.deepcopy(payload["gates"][0]))
    with pytest.raises(ValueError, match="ids must be unique"):
        type(config).model_validate(payload)
