import pytest

from app.api.chat_api import build_rag_citation_payload
from app.evaluation.stage19_rag_quality_gate import stage19_rag_quality_gate
from app.schemas.tool_schema import ToolCallRecord


CATEGORIES = [
    "POLICY", "ORDER", "PRODUCT", "RETURN", "REFUSAL", "AUTHORIZATION",
    "POLICY_RAG", "GUARDRAIL",
]


def report(run_id: str, **overrides):
    seed_by_run = {"run-1": 25001, "run-2": 25002, "run-3": 25003,
                   "one": 25001, "two": 25002, "three": 25003}
    values = {
        "policyQaRecallAt5": 0.93,
        "citationPrecision": 0.97,
        "unsupportedAnswerRate": 0.005,
        "noAnswerPrecision": 0.94,
        "tenantLeakage": 0.0,
        **overrides,
    }
    return {
        "runId": run_id,
        "randomSeed": seed_by_run.get(run_id, 25001),
        "evaluationSetVersion": "evalset-v1",
        "sourceKind": "OFFLINE_CURATED",
        "containsProductionUserData": False,
        "categories": CATEGORIES,
        "publicationVersion": "policy-v7",
        "retrievalEpoch": 42,
        "summary": {key: {"count": 20, "value": value} for key, value in values.items()},
    }


def test_gate_uses_worst_of_three_and_enforces_document_thresholds():
    result = stage19_rag_quality_gate.evaluate([
        report("run-1"),
        report("run-2", policyQaRecallAt5=0.90, citationPrecision=0.95),
        report("run-3", unsupportedAnswerRate=0.01, noAnswerPrecision=0.90),
    ])

    assert result["passed"] is True
    assert result["runCount"] == 3
    assert result["evaluationCaseCount"] == 15
    assert result["worstCaseSummary"]["policyQaRecallAt5"]["value"] == 0.90
    assert result["worstCaseSummary"]["unsupportedAnswerRate"]["value"] == 0.01


def test_gate_blocks_any_failed_worst_run_and_tenant_leakage():
    result = stage19_rag_quality_gate.evaluate([
        report("run-1"), report("run-2"), report("run-3", tenantLeakage=0.01)
    ])

    assert result["passed"] is False
    assert next(item for item in result["checks"] if item["metric"] == "tenantLeakage")["passed"] is False


def test_gate_rejects_replays_mixed_versions_and_online_user_data():
    with pytest.raises(ValueError, match="unique"):
        repeated = [report("same"), report("same"), report("same")]
        for index, item in enumerate(repeated):
            item["randomSeed"] = 25001 + index
        stage19_rag_quality_gate.evaluate(repeated)
    mixed = [report("one"), report("two"), report("three")]
    mixed[2]["evaluationSetVersion"] = "evalset-v2"
    with pytest.raises(ValueError, match="fixed"):
        stage19_rag_quality_gate.evaluate(mixed)
    online = [report("one"), report("two"), report("three")]
    online[1]["containsProductionUserData"] = True
    with pytest.raises(ValueError, match="online user"):
        stage19_rag_quality_gate.evaluate(online)
    wrong_seed = [report("one"), report("two"), report("three")]
    wrong_seed[2]["randomSeed"] = 99999
    with pytest.raises(ValueError, match="releaseSeeds"):
        stage19_rag_quality_gate.evaluate(wrong_seed)


def test_citations_retain_publication_version_and_retrieval_epoch():
    policy = ToolCallRecord(
        name="search_policy_kb",
        arguments={"keyword": "return policy"},
        ok=True,
        result={
            "retrievalStatus": "OK",
            "documents": [{
                "docId": 7,
                "docVersionId": 9,
                "chunkId": 11,
                "title": "Return policy",
                "content": "Returns require approval.",
                "publicationVersion": "doc-7-v3",
                "retrievalEpoch": 42,
            }],
        },
        traceId="trace-stage19-citation",
    )

    payload = build_rag_citation_payload([policy], "trace-stage19-citation")

    assert payload["citations"][0]["publicationVersion"] == "doc-7-v3"
    assert payload["citations"][0]["retrievalEpoch"] == 42
    assert payload["evidence"][0]["publicationVersion"] == "doc-7-v3"
    assert payload["evidence"][0]["retrievalEpoch"] == 42
