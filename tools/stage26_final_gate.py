from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


EXPECTED_IDS = [f"F26-{index:02d}" for index in range(1, 11)]


def read_json(root: Path, relative: str) -> dict[str, Any]:
    return json.loads((root / relative).read_text(encoding="utf-8-sig"))


def _stage25_map(document: dict[str, Any], key: str) -> dict[str, bool]:
    return {item["id"]: bool(item.get(key)) for item in document.get("controls" if key == "implemented" else "productionEvidence", [])}


def _owner_complete(registry: dict[str, Any]) -> bool:
    return bool(registry.get("entries")) and all(
        item.get("status") == "VERIFIED"
        and all(str(item.get(field) or "").strip() for field in ("ownerName", "backup", "approver", "dueDate", "updatedAt"))
        and bool(item.get("evidenceRefs"))
        for item in registry["entries"]
    )


def _signoff_complete(signoff: dict[str, Any]) -> bool:
    return bool(signoff.get("evidenceManifestSha256")) and signoff.get("decision") == "GO" and all(
        item.get("status") == "APPROVED"
        and item.get("approver") and item.get("approvedAt") and item.get("evidenceRefs")
        for item in signoff.get("roles", [])
    )


def evaluate(root: Path, policy: dict[str, Any]) -> dict[str, Any]:
    stage8 = read_json(root, ".acceptance/stage8/encoding-gate.json")
    stage19 = read_json(root, ".acceptance/stage19/final-summary.json")
    stage21 = read_json(root, ".acceptance/stage21/final-summary.json")
    stage22 = read_json(root, ".acceptance/stage22/final-summary.json")
    stage22_e2e = read_json(root, ".acceptance/stage22/e2e-execution-gate.json")
    stage22_security = read_json(root, ".acceptance/stage22/security-gate.json")
    stage23 = read_json(root, ".acceptance/stage23/final-summary.json")
    stage24 = read_json(root, ".acceptance/stage24/final-summary.json")
    stage25 = read_json(root, ".acceptance/stage25/quality-gate.json")
    owners = read_json(root, "docs/operations/stage25-owner-registry.json")
    signoff = read_json(root, "docs/operations/stage23-production-signoff.json")
    blockers = read_json(root, "docs/operations/stage23-release-blockers.json")

    implemented = _stage25_map(stage25, "implemented")
    production = _stage25_map(stage25, "verified")
    open_critical = [item for item in blockers.get("blockers", []) if item.get("severity") in {"P0", "P1"} and item.get("status") != "CLOSED"]

    controls = {
        "F26-01": bool(stage8.get("passed")) and implemented.get("S25-01", False) and implemented.get("S25-02", False),
        "F26-02": implemented.get("S25-03", False),
        "F26-03": implemented.get("S25-04", False),
        "F26-04": implemented.get("S25-05", False) and "SINGLE_TENANT" in stage24.get("resolvedProductionDecisions", []),
        "F26-05": implemented.get("S25-06", False),
        "F26-06": implemented.get("S25-07", False) and bool(stage19.get("engineering", {}).get("fullyCompleted")),
        "F26-07": implemented.get("S25-08", False),
        "F26-08": implemented.get("S25-08", False) and bool(stage21.get("engineering", {}).get("fullyCompleted")),
        "F26-09": implemented.get("S25-09", False) and stage23.get("controlsPassedCount") == 7,
        "F26-10": bool(stage22.get("engineering", {}).get("fullyCompleted")),
    }
    evidence = {
        "F26-01": bool(stage8.get("passed")),
        "F26-02": production.get("S25-03", False),
        "F26-03": production.get("S25-04", False),
        "F26-04": production.get("S25-05", False),
        "F26-05": production.get("S25-06", False),
        "F26-06": production.get("S25-07", False) and bool(stage19.get("production", {}).get("ready")),
        "F26-07": production.get("S25-08", False),
        "F26-08": production.get("S25-08", False) and bool(stage21.get("production", {}).get("ready")),
        "F26-09": production.get("S25-09", False) and _owner_complete(owners) and _signoff_complete(signoff) and not open_critical,
        "F26-10": bool(stage22_e2e.get("passed")) and bool(stage22_security.get("passed")) and bool(stage22.get("production", {}).get("ready")),
    }

    configured = policy.get("conditions") or []
    policy_valid = (
        policy.get("schemaVersion") == "AIMALL_STAGE26_FINAL_GATES_V1"
        and policy.get("releaseVersion") == (root / "docs/RELEASE_VERSION.txt").read_text(encoding="utf-8").strip()
        and [item.get("id") for item in configured] == EXPECTED_IDS
        and all(item.get("name") and item.get("sources") for item in configured)
    )
    results = [{
        "id": item["id"], "name": item["name"],
        "controlImplemented": bool(controls.get(item["id"])),
        "releaseEvidenceSatisfied": bool(evidence.get(item["id"])),
        "passed": bool(controls.get(item["id"]) and evidence.get(item["id"])),
    } for item in configured]
    control_count = sum(item["controlImplemented"] for item in results)
    passed_count = sum(item["passed"] for item in results)
    engineering_passed = policy_valid and control_count == len(EXPECTED_IDS)
    release_passed = engineering_passed and passed_count == len(EXPECTED_IDS)
    facts = {item["id"]: item["passed"] for item in results}
    return {
        "schemaVersion": "AIMALL_STAGE26_FINAL_GATE_RESULT_V1",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "releaseVersion": policy.get("releaseVersion"),
        "engineeringPassedCount": control_count,
        "engineeringTotal": len(EXPECTED_IDS),
        "engineeringPassed": engineering_passed,
        "releasePassedCount": passed_count,
        "releaseTotal": len(EXPECTED_IDS),
        "releasePassed": release_passed,
        "decision": "GO" if release_passed else "NO_GO",
        "openP0P1Count": len(open_critical),
        "evidenceManifestSha256": hashlib.sha256(json.dumps(facts, sort_keys=True).encode()).hexdigest(),
        "conditions": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="AIMall Stage 26 final v1.2 release gate")
    parser.add_argument("--policy", default="docs/operations/stage26-final-gates.json")
    parser.add_argument("--output", default=".acceptance/stage26/final-gate.json")
    parser.add_argument("--require-engineering", action="store_true")
    parser.add_argument("--require-release", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    result = evaluate(root, read_json(root, args.policy))
    output = root / args.output if not Path(args.output).is_absolute() else Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.require_engineering and not result["engineeringPassed"]:
        return 2
    if args.require_release and not result["releasePassed"]:
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
