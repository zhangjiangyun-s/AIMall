from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "tools" / "stage25_v12_gate.py"
SPEC = importlib.util.spec_from_file_location("stage25_v12_gate", MODULE_PATH)
stage25_gate = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(stage25_gate)


def documents():
    load = lambda path: json.loads((ROOT / path).read_text(encoding="utf-8-sig"))
    return (
        load("docs/operations/stage25-controls.json"),
        load("docs/operations/stage25-production-evidence.json"),
    )


def test_engineering_controls_are_complete_but_production_is_blocked():
    policy, production = documents()
    result = stage25_gate.evaluate(ROOT, policy, production)
    assert result["engineeringPassed"] is True
    assert result["engineeringPassedCount"] == 9
    assert result["productionPassed"] is False
    assert result["productionPassedCount"] == 0
    assert result["decision"] == "NOT_PRODUCTION_READY"


def test_production_evidence_requires_named_approval_timestamp_and_reference():
    item = {
        "status": "VERIFIED",
        "owner": "named-owner",
        "approver": "named-approver",
        "verifiedAt": "2026-07-22T00:00:00Z",
        "evidenceRefs": ["immutable://evidence"],
    }
    assert stage25_gate.production_verified(item) is True
    for field, value in (
        ("status", "UNVERIFIED"), ("owner", ""), ("approver", ""),
        ("verifiedAt", ""), ("evidenceRefs", []),
    ):
        incomplete = dict(item)
        incomplete[field] = value
        assert stage25_gate.production_verified(incomplete) is False


def test_owner_registry_structure_does_not_fabricate_production_ownership():
    registry = json.loads(
        (ROOT / "docs/operations/stage25-owner-registry.json").read_text(encoding="utf-8-sig")
    )
    assert stage25_gate._owner_registry_valid(registry) is True
    assert all(not entry["ownerName"] for entry in registry["entries"])
    assert all(entry["status"] == "UNVERIFIED" for entry in registry["entries"])


def test_evalset_manifest_hash_is_immutable_and_has_three_fixed_seeds():
    manifest = json.loads(
        (ROOT / "aimall-ai-service/data/evaluation/evalset-v1-manifest.json").read_text(encoding="utf-8-sig")
    )
    case_bytes = (ROOT / "aimall-ai-service/data/evaluation/evalset-v1.jsonl").read_bytes()
    assert manifest["caseFileSha256"] == hashlib.sha256(case_bytes).hexdigest()
    assert manifest["releaseSeeds"] == [25001, 25002, 25003]
    assert manifest["expectedCaseCount"] == 15


def test_production_gate_stays_closed_when_only_one_item_is_marked_verified():
    policy, production = documents()
    modified = copy.deepcopy(production)
    modified["evidence"][0].update({
        "status": "VERIFIED",
        "owner": "owner",
        "approver": "approver",
        "verifiedAt": "2026-07-22T00:00:00Z",
        "evidenceRefs": ["immutable://money-evidence"],
    })
    result = stage25_gate.evaluate(ROOT, policy, modified)
    assert result["productionPassedCount"] == 1
    assert result["productionPassed"] is False
