import copy

import pytest

from app.evaluation import load_evaluation_dataset, rag_evaluation_scorer


MANIFEST = "data/evaluation/manifest.json"


def run_payload(*results):
    return {
        "runId": "EVAL_RAG_TEST",
        "datasetId": "aimall-agent-baseline",
        "datasetVersion": "1.2.3",
        "updatedAt": "2026-07-15T00:00:00+08:00",
        "results": list(results),
    }


def result(case_id, answer, citations, *, retrieval_status="OK", **done_updates):
    done = {
        "type": "done",
        "intent": "POLICY_QA",
        "retrievalStatus": retrieval_status,
        "refusalReason": None,
        "ragCitations": citations,
        "reflection": {"status": "PASSED", "action": "ACCEPT"},
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


def citation(citation_id, title, source, snippet):
    return {"id": str(citation_id), "title": title, "source": source, "snippet": snippet}


def test_cart_retrieval_and_citation_metrics_pass():
    dataset = load_evaluation_dataset(MANIFEST)
    run = run_payload(
        result(
            "AIM-EVAL-POLICY_RAG-001",
            "购物车不代表订单成立，也不锁定价格和库存。[1]",
            [
                citation(
                    1,
                    "购物车规则",
                    "POLICY#6#chunk-1",
                    "购物车不代表订单成立，不锁定价格，不锁定库存。",
                )
            ],
        )
    )
    report = rag_evaluation_scorer.score(dataset, run)
    score = report["caseScores"][0]

    assert score["scoreStatus"] == "PASSED"
    assert score["metrics"]["recallAtK"] == 1.0
    assert score["metrics"]["mrr"] == 1.0
    assert score["metrics"]["ndcg"] == 1.0
    assert score["metrics"]["citationAccuracy"] == 1.0
    assert score["metrics"]["citationFaithfulness"]["value"] == 1.0


def test_duplicate_chunks_do_not_gain_relevance_twice():
    dataset = load_evaluation_dataset(MANIFEST)
    run = run_payload(
        result(
            "AIM-EVAL-POLICY_RAG-001",
            "购物车不锁定价格。[1]",
            [
                citation(1, "购物车规则", "POLICY#6#chunk-1", "购物车不锁定价格，不锁定库存。"),
                citation(2, "购物车规则", "POLICY#6#chunk-2", "加入购物车前需要选择规格。"),
            ],
        )
    )
    score = rag_evaluation_scorer.score(dataset, run)["caseScores"][0]

    assert score["metrics"]["matchedRelevantEvidenceIds"] == ["cart-policy"]
    assert score["metrics"]["ndcg"] == 1.0


def test_graded_shipping_ranking_calculates_ndcg():
    dataset = load_evaluation_dataset(MANIFEST)
    run = run_payload(
        result(
            "AIM-EVAL-HYBRID-001",
            "普通现货在支付成功后 48 小时内发货。[1][2]",
            [
                citation(1, "发货规则", "POLICY#2#chunk-1", "订单支付成功后48小时内安排发货。"),
                citation(
                    2,
                    "配送与发货规则",
                    "POLICY#7#chunk-2",
                    "普通现货商品在订单支付成功后，平台通常在 48 小时内安排发货。",
                ),
            ],
        )
    )
    score = rag_evaluation_scorer.score(dataset, run)["caseScores"][0]

    assert score["metrics"]["recallAtK"] == 1.0
    assert score["metrics"]["mrr"] == 1.0
    assert 0.75 <= score["metrics"]["ndcg"] < 1.0


def test_no_match_refusal_passes_without_retrieval_metrics():
    dataset = load_evaluation_dataset(MANIFEST)
    no_match = result(
        "AIM-EVAL-POLICY_RAG-002",
        "没有找到可核验的政策依据。",
        [],
        retrieval_status="NO_MATCH",
        refusalReason="NO_MATCH",
        reflection={"status": "REFUSED", "action": "REFUSE"},
    )
    score = rag_evaluation_scorer.score(dataset, run_payload(no_match))["caseScores"][0]

    assert score["scoreStatus"] == "PASSED"
    assert score["metrics"]["noMatchCorrect"] == 1.0
    assert score["metrics"]["recallAtK"] is None


def test_no_match_with_citations_is_a_failure():
    dataset = load_evaluation_dataset(MANIFEST)
    bad = result(
        "AIM-EVAL-POLICY_RAG-002",
        "火星配送规则如下。[1]",
        [citation(1, "配送规则", "POLICY#99#chunk-1", "普通配送说明。")],
        retrieval_status="OK",
    )
    score = rag_evaluation_scorer.score(dataset, run_payload(bad))["caseScores"][0]

    assert score["scoreStatus"] == "FAILED"
    assert {"no_match_status", "no_match_has_no_citations", "no_match_refusal"}.issubset(score["failureCodes"])


def test_invalid_citation_and_unsupported_number_fail_faithfulness():
    dataset = load_evaluation_dataset(MANIFEST)
    bad = result(
        "AIM-EVAL-POLICY_RAG-001",
        "购物车价格会锁定 15 天。[9]",
        [citation(1, "购物车规则", "POLICY#6#chunk-1", "购物车不锁定价格，不锁定库存。")],
    )
    score = rag_evaluation_scorer.score(dataset, run_payload(bad))["caseScores"][0]

    assert score["metrics"]["citationValidity"] == 0.0
    assert score["metrics"]["citationFaithfulness"]["value"] == 0.0
    assert {"citation_accuracy", "citation_coverage", "citation_faithfulness"}.issubset(score["failureCodes"])


def test_evidence_only_output_scores_exact_citation_blocks_as_faithful():
    dataset = load_evaluation_dataset(MANIFEST)
    evidence = citation(
        1,
        "Shopping cart policy",
        "POLICY#6#chunk-1",
        "The shopping cart does not lock price or stock.",
    )
    run = run_payload(
        result(
            "AIM-EVAL-POLICY_RAG-001",
            "Verified evidence only:\n[1] Shopping cart policy\nThe shopping cart does not lock price or stock.",
            [evidence],
            reflection={"status": "DEGRADED", "action": "RETURN_EVIDENCE_ONLY"},
        )
    )

    score = rag_evaluation_scorer.score(dataset, run)["caseScores"][0]

    assert score["metrics"]["citationFaithfulness"]["value"] == 1.0


def test_evidence_only_output_rejects_tampered_citation_blocks():
    dataset = load_evaluation_dataset(MANIFEST)
    evidence = citation(
        1,
        "Shopping cart policy",
        "POLICY#6#chunk-1",
        "The shopping cart does not lock price or stock.",
    )
    run = run_payload(
        result(
            "AIM-EVAL-POLICY_RAG-001",
            "Verified evidence only:\n[1] Shopping cart policy\nThe shopping cart locks stock for 15 days.",
            [evidence],
            reflection={"status": "DEGRADED", "action": "RETURN_EVIDENCE_ONLY"},
        )
    )

    score = rag_evaluation_scorer.score(dataset, run)["caseScores"][0]

    assert score["metrics"]["citationFaithfulness"]["value"] == 0.0


def test_execution_failure_contributes_zero_rag_metrics():
    dataset = load_evaluation_dataset(MANIFEST)
    failed = {"caseId": "AIM-EVAL-POLICY_RAG-001", "status": "TIMEOUT"}
    report = rag_evaluation_scorer.score(dataset, run_payload(failed))

    assert report["caseScores"][0]["scoreStatus"] == "FAILED"
    assert report["summary"]["recallAtK"]["value"] == 0.0
    assert report["summary"]["passRate"]["value"] == 0.0


def test_rag_run_version_mismatch_is_rejected():
    dataset = load_evaluation_dataset(MANIFEST)
    payload = run_payload()
    payload["datasetVersion"] = "1.0.0"
    with pytest.raises(ValueError, match="datasetVersion"):
        rag_evaluation_scorer.score(dataset, payload)


def test_historical_v1_dataset_remains_loadable():
    historical = load_evaluation_dataset("data/evaluation/manifest-v1.0.0.json")

    assert historical.manifest.version == "1.0.0"
    assert len(historical.cases) == 20
    assert all(case.expected.rag is None for case in historical.cases)
