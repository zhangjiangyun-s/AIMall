from __future__ import annotations

import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "tools" / "stage24_reflection_gate.py"
SPEC = importlib.util.spec_from_file_location("stage24_reflection_gate", MODULE_PATH)
reflection_gate = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(reflection_gate)


def _documents():
    load = lambda path: json.loads((ROOT / path).read_text(encoding="utf-8-sig"))
    return (
        load("docs/operations/stage24-reflection-controls.json"),
        load("docs/operations/stage24-external-decisions.json"),
    )


def test_current_reflection_controls_are_complete_but_production_is_not_ready():
    policy, decisions = _documents()
    result = reflection_gate.evaluate(ROOT, policy, decisions)
    assert result["engineeringPassed"] is True
    assert result["engineeringPassedCount"] == 4
    assert result["productionPassed"] is False
    assert result["productionPassedCount"] == 1
    assert result["decision"] == "NOT_PRODUCTION_READY"
    assert [item["id"] for item in result["externalDecisions"] if item["resolved"]] == ["D24-04"]


def test_external_decision_requires_decision_approver_and_evidence():
    item = {
        "status": "RESOLVED",
        "decision": "approved-value",
        "owner": "owner",
        "approver": "approver",
        "evidenceRefs": ["evidence.json"],
    }
    assert reflection_gate.decision_complete(item) is True
    for field, value in (("decision", ""), ("approver", ""), ("evidenceRefs", [])):
        incomplete = dict(item)
        incomplete[field] = value
        assert reflection_gate.decision_complete(incomplete) is False


def test_client_trace_is_not_reused_as_trusted_ai_trace():
    middleware = (ROOT / "aimall-ai-service/app/observability/middleware.py").read_text(encoding="utf-8")
    assert "trace_id = new_trace_id()" in middleware
    assert "client_trace_id = resolve_client_trace_id(candidate)" in middleware
    assert "trace_id = resolve_trace_id(candidate)" not in middleware


def test_manifest_is_stable_for_identical_reflection_facts():
    policy, decisions = _documents()
    first = reflection_gate.evaluate(ROOT, policy, decisions)
    second = reflection_gate.evaluate(ROOT, policy, decisions)
    assert first["evidenceManifestSha256"] == second["evidenceManifestSha256"]
