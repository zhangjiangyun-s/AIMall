from __future__ import annotations

import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("stage5_business_gate", ROOT / "tools/stage5_business_gate.py")
stage5_gate = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(stage5_gate)


def test_stage5_engineering_controls_are_complete():
    policy = json.loads((ROOT / "docs/operations/stage5-controls.json").read_text(encoding="utf-8-sig"))
    result = stage5_gate.evaluate(ROOT, policy)
    assert result["engineeringPassed"] is True
    assert result["engineeringPassedCount"] == 8
    assert result["productionPassed"] is False


def test_stage5_controls_are_ordered_and_complete():
    policy = json.loads((ROOT / "docs/operations/stage5-controls.json").read_text(encoding="utf-8-sig"))
    assert [item["id"] for item in policy["controls"]] == [f"P5-{i:02d}" for i in range(1, 9)]
