from __future__ import annotations

import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "tools" / "stage23_release_gate.py"
SPEC = importlib.util.spec_from_file_location("stage23_release_gate", MODULE_PATH)
release_gate = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(release_gate)


def _current_documents():
    load = lambda path: json.loads((ROOT / path).read_text(encoding="utf-8-sig"))
    return (
        load("docs/operations/stage23-release-gates.json"),
        load("docs/operations/stage23-release-blockers.json"),
        load("docs/operations/stage23-production-signoff.json"),
    )


def _engineering_complete_evidence():
    return {
        "stage7": {"passed": True},
        "stage8": {"passed": True},
        "stage14": {"structurallyPassed": True},
        "stage15": {"engineeringPassed": True, "productionPassed": False},
        "stage16": {"engineeringPassed": True, "productionPassed": False},
        "stage17": {"engineeringPassed": True, "productionPassed": False},
        "stage18": {"engineeringPassed": True, "productionPassed": False},
        "stage19": {"engineeringFullyCompleted": True, "productionReady": False},
        "stage20": {"engineeringPassed": True, "productionPassed": False},
        "stage21": {"engineeringPassed": True, "productionPassed": False},
        "stage22": {"engineeringPassed": True, "productionPassed": False},
        "stage22_security": {"passed": False},
        "stage22_e2e": {"passed": False},
    }


def test_current_release_is_fail_closed_with_all_controls_implemented():
    policy, ledger, signoff = _current_documents()
    result = release_gate.evaluate(ROOT, policy, ledger, signoff, _engineering_complete_evidence())
    assert result["controlsPassed"] is True
    assert result["controlsPassedCount"] == 7
    assert result["releasePassedCount"] == 0
    assert result["decision"] == "NO_GO"
    assert result["openP0P1Count"] == 9
    assert result["signoffsComplete"] is False
    assert result["gates"][0]["prerequisitesSatisfied"] is True
    assert all(not gate["prerequisitesSatisfied"] for gate in result["gates"][1:])


def test_signoff_requires_six_approvers_timestamps_evidence_and_manifest_hash():
    _, _, pending = _current_documents()
    assert release_gate.signoffs_complete(pending) is False
    completed = json.loads(json.dumps(pending))
    completed["evidenceManifestSha256"] = "a" * 64
    completed["decision"] = "GO"
    for item in completed["roles"]:
        item.update({
            "status": "APPROVED",
            "approver": f"{item['role']}-owner",
            "approvedAt": "2026-07-21T14:00:00Z",
            "evidenceRefs": ["immutable-manifest.json"],
        })
    assert release_gate.signoffs_complete(completed) is True
    completed["roles"][0]["evidenceRefs"] = []
    assert release_gate.signoffs_complete(completed) is False


def test_evidence_manifest_hash_is_stable_for_same_release_facts():
    policy, ledger, signoff = _current_documents()
    evidence = _engineering_complete_evidence()
    first = release_gate.evaluate(ROOT, policy, ledger, signoff, evidence)
    second = release_gate.evaluate(ROOT, policy, ledger, signoff, evidence)
    assert first["evidenceManifestSha256"] == second["evidenceManifestSha256"]


def test_missing_runtime_evidence_fails_closed():
    policy, ledger, signoff = _current_documents()
    result = release_gate.evaluate(ROOT, policy, ledger, signoff, {})
    assert result["controlsPassed"] is False
    assert result["releasePassed"] is False
    assert result["decision"] == "NO_GO"
