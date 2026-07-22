import copy

import pytest

from app.evaluation import deterministic_evaluation_scorer, load_evaluation_dataset


MANIFEST = "data/evaluation/manifest.json"


def result(case_id, *, intent, answer="", tools=None, **done_updates):
    done = {
        "type": "done",
        "intent": intent,
        "toolCalls": tools or [],
        "businessEvidence": [],
        "pendingActions": [],
        "guardrails": [],
    }
    done.update(done_updates)
    return {
        "caseId": case_id,
        "status": "SUCCESS",
        "answer": answer,
        "done": done,
        "events": [],
    }


def run_payload(*results):
    return {
        "runId": "EVAL_TEST",
        "datasetId": "aimall-agent-baseline",
        "datasetVersion": "1.2.3",
        "updatedAt": "2026-07-15T00:00:00+08:00",
        "results": list(results),
    }


def test_order_case_passes_all_deterministic_checks():
    dataset = load_evaluation_dataset(MANIFEST)
    order = result(
        "AIM-EVAL-ORDER-001",
        intent="ORDER_QA",
        answer="订单当前为待发货。",
        tools=[{"name": "get_my_order_detail", "ok": True}],
        businessEvidence=[{"id": "B1"}],
        reflection={"status": "PASSED", "action": "ACCEPT"},
    )
    report = deterministic_evaluation_scorer.score(dataset, run_payload(order))
    score = report["caseScores"][0]

    assert score["scoreStatus"] == "PASSED"
    assert score["deterministicPassed"] is True
    assert report["summary"]["intentAccuracy"]["value"] == 1.0
    assert report["summary"]["toolPrecision"]["value"] == 1.0
    assert report["summary"]["toolRecall"]["value"] == 1.0
    assert report["summary"]["taskCompletionRate"]["value"] == 1.0


def test_unexpected_tool_reduces_precision_without_reducing_recall():
    dataset = load_evaluation_dataset(MANIFEST)
    order = result(
        "AIM-EVAL-ORDER-001",
        intent="ORDER_QA",
        answer="订单当前为待发货。",
        tools=[
            {"name": "get_my_order_detail", "ok": True},
            {"name": "search_policy_kb", "ok": True},
        ],
        businessEvidence=[{"id": "B1"}],
        reflection={"status": "PASSED", "action": "ACCEPT"},
    )
    summary = deterministic_evaluation_scorer.score(dataset, run_payload(order))["summary"]

    assert summary["toolPrecision"]["value"] == 0.5
    assert summary["toolRecall"]["value"] == 1.0


def test_wrong_intent_missing_evidence_and_answer_are_explained():
    dataset = load_evaluation_dataset(MANIFEST)
    order = result(
        "AIM-EVAL-ORDER-001",
        intent="GENERAL_QA",
        answer="暂时不清楚。",
        tools=[{"name": "get_my_order_detail", "ok": True}],
        reflection={"status": "PASSED"},
    )
    score = deterministic_evaluation_scorer.score(dataset, run_payload(order))["caseScores"][0]

    assert score["scoreStatus"] == "FAILED"
    assert {"intent", "business_evidence", "answer_contains"}.issubset(score["failureCodes"])


def test_guardrail_block_is_a_successful_quality_result():
    dataset = load_evaluation_dataset(MANIFEST)
    blocked = result(
        "AIM-EVAL-GUARDRAIL-001",
        intent="SAFETY_BLOCKED",
        answer="无法处理该请求。",
        guardrails=[{"type": "guardrail", "action": "BLOCK"}],
    )
    score = deterministic_evaluation_scorer.score(dataset, run_payload(blocked))["caseScores"][0]

    assert score["scoreStatus"] == "PASSED"
    assert score["deterministicPassed"] is True


def test_pending_action_state_is_scored():
    dataset = load_evaluation_dataset(MANIFEST)
    hitl = result(
        "AIM-EVAL-HITL-001",
        intent="ORDER_QA",
        answer="已创建待确认操作，确认后执行。",
        tools=[{"name": "cancel_order_confirmed", "ok": True}],
        pendingActions=[{"actionId": "pa-1", "status": "PENDING"}],
        reflection={"status": "PASSED", "action": "ACCEPT"},
    )
    score = deterministic_evaluation_scorer.score(dataset, run_payload(hitl))["caseScores"][0]

    assert score["scoreStatus"] == "PASSED"
    assert score["taskCompletionPassed"] is True


def test_semantic_case_waits_for_later_semantic_scoring():
    dataset = load_evaluation_dataset(MANIFEST)
    policy = result(
        "AIM-EVAL-POLICY_RAG-001",
        intent="POLICY_QA",
        answer="购物车不锁定价格和库存。[1]",
        tools=[{"name": "search_policy_kb", "ok": True}],
        reflection={"status": "PASSED", "action": "ACCEPT"},
    )
    score = deterministic_evaluation_scorer.score(dataset, run_payload(policy))["caseScores"][0]

    assert score["deterministicPassed"] is True
    assert score["scoreStatus"] == "PENDING_SEMANTIC"


def test_unsupported_case_is_not_counted_as_failure():
    dataset = load_evaluation_dataset(MANIFEST)
    unsupported = {
        "caseId": "AIM-EVAL-TOOL_FAILURE-001",
        "status": "UNSUPPORTED",
        "errorType": "FAULT_INJECTION_NOT_CONFIGURED",
    }
    report = deterministic_evaluation_scorer.score(dataset, run_payload(unsupported))

    assert report["caseScores"][0]["scoreStatus"] == "NOT_EVALUATED"
    assert report["summary"]["deterministicPassRate"]["denominator"] == 0


def test_execution_failure_reduces_task_intent_tool_and_evidence_metrics():
    dataset = load_evaluation_dataset(MANIFEST)
    failed = {
        "caseId": "AIM-EVAL-ORDER-001",
        "status": "TIMEOUT",
        "errorType": "CASE_TIMEOUT",
    }
    report = deterministic_evaluation_scorer.score(dataset, run_payload(failed))
    summary = report["summary"]

    assert summary["deterministicPassRate"]["value"] == 0.0
    assert summary["intentAccuracy"]["value"] == 0.0
    assert summary["toolRecall"]["value"] == 0.0
    assert summary["taskCompletionRate"]["value"] == 0.0
    assert summary["businessEvidenceCompliance"]["value"] == 0.0


def test_run_dataset_mismatch_and_duplicate_results_are_rejected():
    dataset = load_evaluation_dataset(MANIFEST)
    payload = run_payload(result("AIM-EVAL-ORDER-001", intent="ORDER_QA"))
    mismatch = copy.deepcopy(payload)
    mismatch["datasetVersion"] = "2.0.0"
    with pytest.raises(ValueError, match="datasetVersion"):
        deterministic_evaluation_scorer.score(dataset, mismatch)

    duplicate = copy.deepcopy(payload)
    duplicate["results"].append(copy.deepcopy(duplicate["results"][0]))
    with pytest.raises(ValueError, match="duplicate case ids"):
        deterministic_evaluation_scorer.score(dataset, duplicate)
