from app.evaluation.rag_mode_quality_gate import rag_mode_quality_gate
from app.tools.java_client import JavaClient
from app.rag.milvus_store import MilvusVectorStore


def metric(value):
    return {"count": 10, "value": value}


def test_each_canonical_rag_mode_has_an_explicit_quality_gate():
    summary = {
        "recallAtK": metric(1.0),
        "mrr": metric(1.0),
        "citationAccuracy": metric(1.0),
        "citationFaithfulness": metric(1.0),
    }

    for mode in ("DOC_ONLY", "HYBRID", "VECTOR"):
        result = rag_mode_quality_gate.evaluate(mode, summary)
        assert result["passed"] is True
        assert result["retrievalMode"] == mode
        assert {item["metric"] for item in result["checks"]} == {
            "recallAtK", "mrr", "citationAccuracy", "hallucinationRate"
        }


def test_rag_quality_gate_rejects_hallucination_and_low_retrieval_metrics():
    result = rag_mode_quality_gate.evaluate("VECTOR", {
        "recallAtK": metric(0.2),
        "mrr": metric(0.3),
        "citationAccuracy": metric(0.4),
        "citationFaithfulness": metric(0.5),
    })

    assert result["passed"] is False
    assert all(item["passed"] is False for item in result["checks"])


def test_source_trust_only_breaks_equal_rrf_scores_and_is_bounded():
    client = JavaClient()
    low_trust = {"chunkId": 1, "contentHash": "low", "sourceTrustScore": -3}
    high_trust = {"chunkId": 2, "contentHash": "high", "sourceTrustScore": 2}

    result = client._rrf_fuse([low_trust, high_trust], [], 2)

    assert [item["chunkId"] for item in result] == [1, 2]
    assert result[0]["sourceTrustScore"] == 0.0
    assert result[1]["sourceTrustScore"] == 1.0

    tied = client._rrf_fuse([low_trust], [high_trust], 2)
    assert tied[0]["chunkId"] == 2


def test_milvus_scope_filter_uses_explicit_role_and_utc_bounds():
    expression = JavaClient()._milvus_scope_filter({"tenantId": "tenant-a", "role": "MEMBER"})

    assert 'tenant_id == "tenant-a"' in expression
    assert "role_member == true" in expression
    assert "effective_time" in expression and "expire_time" in expression
    assert " like " not in expression


def test_role_scope_is_materialized_into_fail_closed_boolean_fields():
    store = MilvusVectorStore()

    assert store._role_access_flags("") == {
        "role_public": True, "role_member": True, "role_admin": True
    }
    assert store._role_access_flags("MEMBER") == {
        "role_public": False, "role_member": True, "role_admin": True
    }
    assert store._role_access_flags("ADMIN_ONLY") == {
        "role_public": False, "role_member": False, "role_admin": True
    }
