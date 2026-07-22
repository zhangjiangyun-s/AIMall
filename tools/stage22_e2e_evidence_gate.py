from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


GROUPS = ("business", "database", "event")


def validate_catalog(root: Path, catalog: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    cases = catalog.get("cases") if isinstance(catalog.get("cases"), list) else []
    if catalog.get("schemaVersion") != "AIMALL_STAGE22_E2E_CATALOG_V1":
        errors.append("invalid catalog schemaVersion")
    if len(cases) != 10:
        errors.append(f"expected 10 cases, found {len(cases)}")
    expected = {f"E2E-{index:02d}" for index in range(1, 11)}
    actual = {str(case.get("id")) for case in cases}
    for missing in sorted(expected - actual):
        errors.append(f"missing case: {missing}")
    for case in cases:
        case_id = str(case.get("id") or "UNKNOWN")
        if str(case.get("providerMode") or "").upper() in {"SIMULATE", "FIXED_SUCCESS", ""}:
            errors.append(f"{case_id}: invalid provider mode")
        assertions = case.get("assertions") if isinstance(case.get("assertions"), dict) else {}
        for group in GROUPS:
            if not isinstance(assertions.get(group), list) or not assertions[group]:
                errors.append(f"{case_id}: missing {group} assertion")
        automation = case.get("automation") if isinstance(case.get("automation"), list) else []
        if not automation:
            errors.append(f"{case_id}: missing automation references")
        for reference in automation:
            if not (root / str(reference)).exists():
                errors.append(f"{case_id}: automation reference missing: {reference}")
    return errors


def validate_execution(case: dict[str, Any], evidence: dict[str, Any]) -> list[str]:
    case_id = str(case["id"])
    errors: list[str] = []
    if evidence.get("caseId") != case_id:
        errors.append(f"{case_id}: evidence caseId mismatch")
    if evidence.get("environment") not in {"sandbox", "production-equivalent"}:
        errors.append(f"{case_id}: invalid environment")
    if str(evidence.get("providerMode") or "").upper() in {"SIMULATE", "FIXED_SUCCESS", ""}:
        errors.append(f"{case_id}: execution used forbidden provider")
    for field in ("runId", "sourceRevision", "startedAt", "finishedAt"):
        if not str(evidence.get(field) or "").strip():
            errors.append(f"{case_id}: missing {field}")
    assertions = evidence.get("assertions") if isinstance(evidence.get("assertions"), dict) else {}
    for group in GROUPS:
        values = assertions.get(group) if isinstance(assertions.get(group), list) else []
        if not values:
            errors.append(f"{case_id}: no executed {group} assertions")
        elif any(not isinstance(item, dict) or item.get("passed") is not True or not item.get("evidence") for item in values):
            errors.append(f"{case_id}: failed/incomplete {group} assertion")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate AIMall Stage 22 three-group E2E evidence")
    parser.add_argument("--catalog", default="docs/operations/stage22-e2e-cases.json")
    parser.add_argument("--evidence-dir", default=".acceptance/stage22/e2e")
    parser.add_argument("--output", default=".acceptance/stage22/e2e-gate.json")
    parser.add_argument("--require-execution", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    resolve = lambda value: Path(value) if Path(value).is_absolute() else root / value
    catalog = json.loads(resolve(args.catalog).read_text(encoding="utf-8"))
    errors = validate_catalog(root, catalog)
    executed = 0
    if args.require_execution:
        evidence_dir = resolve(args.evidence_dir)
        for case in catalog["cases"]:
            path = evidence_dir / f"{case['id']}.json"
            if not path.exists():
                errors.append(f"{case['id']}: execution evidence missing")
                continue
            executed += 1
            errors.extend(validate_execution(case, json.loads(path.read_text(encoding="utf-8"))))
    result = {
        "stage": 22,
        "schemaVersion": "AIMALL_STAGE22_E2E_GATE_V1",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "catalogCases": len(catalog.get("cases", [])),
        "executedCases": executed,
        "executionRequired": args.require_execution,
        "errors": errors,
        "passed": not errors,
    }
    output = resolve(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
