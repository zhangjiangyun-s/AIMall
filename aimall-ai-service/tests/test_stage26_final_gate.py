from __future__ import annotations

import importlib.util
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("stage26_final_gate", ROOT / "tools/stage26_final_gate.py")
gate = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(gate)


def current_result():
    policy = json.loads((ROOT / "docs/operations/stage26-final-gates.json").read_text(encoding="utf-8-sig"))
    return gate.evaluate(ROOT, policy)


def test_all_final_gate_controls_are_implemented():
    result = current_result()
    assert result["engineeringPassed"] is True
    assert result["engineeringPassedCount"] == 10


def test_current_release_stays_fail_closed():
    result = current_result()
    assert result["releasePassed"] is False
    assert result["decision"] == "NO_GO"
    assert result["openP0P1Count"] == 9


def test_only_document_consistency_is_currently_satisfied():
    result = current_result()
    assert result["releasePassedCount"] == 1
    assert result["conditions"][0]["passed"] is True
    assert all(not item["passed"] for item in result["conditions"][1:])


def test_condition_ids_are_complete_and_ordered():
    result = current_result()
    assert [item["id"] for item in result["conditions"]] == [f"F26-{i:02d}" for i in range(1, 11)]
