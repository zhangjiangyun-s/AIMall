import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from app.evaluation import EvaluationCase, EvaluationManifest, load_evaluation_dataset


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "data" / "evaluation" / "manifest.json"
HISTORICAL_MANIFEST_1_1 = ROOT / "data" / "evaluation" / "manifest-v1.1.0.json"
HISTORICAL_MANIFEST_1_2 = ROOT / "data" / "evaluation" / "manifest-v1.2.0.json"


def test_baseline_dataset_is_valid_and_balanced():
    dataset = load_evaluation_dataset(MANIFEST)

    assert dataset.manifest.version == "1.2.3"
    assert len(dataset.cases) == 20
    assert len({case.id for case in dataset.cases}) == 20
    assert {case.category.value for case in dataset.cases} == {
        "PRODUCT",
        "ORDER",
        "POLICY_RAG",
        "HYBRID",
        "MEMORY",
        "PERMISSION",
        "GUARDRAIL",
        "HITL",
        "TOOL_FAILURE",
        "REFLECTION",
    }
    assert sum(case.deterministicOnly for case in dataset.cases) == 16
    assert sum(not case.deterministicOnly for case in dataset.cases) == 4


def test_all_live_http_cases_use_authenticated_ai_sessions():
    dataset = load_evaluation_dataset(MANIFEST)

    live_cases = [case for case in dataset.cases if not case.fixture.faultInjections]
    assert live_cases
    assert all(case.fixture.authRequired and case.fixture.userFixture for case in live_cases)


def test_historical_1_1_dataset_remains_reproducible():
    dataset = load_evaluation_dataset(HISTORICAL_MANIFEST_1_1)

    assert dataset.manifest.version == "1.1.0"
    assert len(dataset.cases) == 20


def test_historical_1_2_dataset_remains_reproducible():
    dataset = load_evaluation_dataset(HISTORICAL_MANIFEST_1_2)

    assert dataset.manifest.version == "1.2.0"
    assert len(dataset.cases) == 20


def test_required_and_forbidden_tools_cannot_overlap():
    with pytest.raises(ValidationError, match="cannot overlap"):
        EvaluationCase.model_validate(
            minimal_case(
                expected={
                    "requiredTools": ["search_products"],
                    "forbiddenTools": ["search_products"],
                }
            )
        )


def test_successful_tools_must_be_required():
    with pytest.raises(ValidationError, match="subset"):
        EvaluationCase.model_validate(
            minimal_case(expected={"requiredSuccessfulTools": ["search_products"]})
        )


def test_authenticated_case_requires_named_fixture_user():
    with pytest.raises(ValidationError, match="must define userFixture"):
        EvaluationCase.model_validate(
            minimal_case(fixture={"authRequired": True})
        )


def test_case_requires_at_least_one_machine_readable_expectation():
    with pytest.raises(ValidationError, match="at least one expectation"):
        EvaluationCase.model_validate(minimal_case(expected={}))


def test_fault_injection_tool_must_be_required():
    with pytest.raises(ValidationError, match="fault injection tools"):
        EvaluationCase.model_validate(
            minimal_case(
                fixture={
                    "faultInjections": [
                        {"toolName": "search_products", "behavior": "TIMEOUT"}
                    ]
                }
            )
        )


def test_citation_case_requires_rag_tool():
    with pytest.raises(ValidationError, match="must require search_policy_kb"):
        EvaluationCase.model_validate(minimal_case(expected={"requireCitations": True}))


def test_semantic_rubric_weights_must_sum_to_one():
    with pytest.raises(ValidationError, match="weights must sum to 1"):
        EvaluationCase.model_validate(
            minimal_case(
                deterministicOnly=False,
                semanticRubrics=[{"criterion": "相关", "weight": 0.4}],
            )
        )


def test_manifest_rejects_path_traversal():
    with pytest.raises(ValidationError, match="inside the dataset directory"):
        EvaluationManifest.model_validate(
            {
                "datasetId": "test-dataset",
                "version": "1.1.0",
                "createdAt": "2026-07-15T00:00:00+08:00",
                "description": "test",
                "caseFiles": ["../cases.jsonl"],
                "expectedCaseCount": 1,
            }
        )


def test_loader_rejects_duplicate_ids(tmp_path):
    case = minimal_case()
    (tmp_path / "cases.jsonl").write_text(
        "\n".join((json.dumps(case, ensure_ascii=False), json.dumps(case, ensure_ascii=False))),
        encoding="utf-8",
    )
    (tmp_path / "manifest.json").write_text(
        json.dumps(
            {
                "datasetId": "test-dataset",
                "version": "1.1.0",
                "createdAt": "2026-07-15T00:00:00+08:00",
                "description": "test",
                "caseFiles": ["cases.jsonl"],
                "expectedCaseCount": 2,
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="duplicate evaluation case id"):
        load_evaluation_dataset(tmp_path / "manifest.json")


def minimal_case(**updates):
    value = {
        "id": "AIM-EVAL-PRODUCT-999",
        "datasetVersion": "1.1.0",
        "category": "PRODUCT",
        "riskLevel": "LOW",
        "title": "test",
        "query": "test query",
        "expected": {"intentAnyOf": ["PRODUCT_QA"]},
        "groundTruth": [
            {"statement": "test fact", "sourceType": "EXPECTED_BEHAVIOR"}
        ],
        "deterministicOnly": True,
        "source": "unit test",
    }
    value.update(updates)
    return value
