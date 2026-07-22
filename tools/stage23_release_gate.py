from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


STAGE_EVIDENCE_PATHS = {
    "stage7": ".acceptance/stage7/security-gate.json",
    "stage8": ".acceptance/stage8/encoding-gate.json",
    "stage14": ".acceptance/stage14/ledger-gate.json",
    "stage15": ".acceptance/stage15/final-summary.json",
    "stage16": ".acceptance/stage16/final-summary.json",
    "stage17": ".acceptance/stage17/final-summary.json",
    "stage18": ".acceptance/stage18/final-summary.json",
    "stage19": ".acceptance/stage19/quality-gate.json",
    "stage20": ".acceptance/stage20/quality-gate.json",
    "stage21": ".acceptance/stage21/quality-gate.json",
    "stage22": ".acceptance/stage22/quality-gate.json",
    "stage22_security": ".acceptance/stage22/security-gate.json",
    "stage22_e2e": ".acceptance/stage22/e2e-execution-gate.json",
}


def read_json(root: Path, relative: str) -> dict[str, Any]:
    return json.loads((root / relative).read_text(encoding="utf-8-sig"))


def load_stage_evidence(root: Path) -> dict[str, dict[str, Any]]:
    evidence: dict[str, dict[str, Any]] = {}
    for name, relative in STAGE_EVIDENCE_PATHS.items():
        try:
            evidence[name] = read_json(root, relative)
        except FileNotFoundError:
            evidence[name] = {}
    return evidence


def bool_at(document: dict[str, Any], *paths: tuple[str, ...]) -> bool:
    for path in paths:
        value: Any = document
        for key in path:
            if not isinstance(value, dict) or key not in value:
                value = None
                break
            value = value[key]
        if value is True:
            return True
    return False


def signoffs_complete(signoff: dict[str, Any]) -> bool:
    expected = {"QA", "Security", "DBA", "SRE", "Backend", "Business"}
    roles = signoff.get("roles") if isinstance(signoff.get("roles"), list) else []
    if {item.get("role") for item in roles} != expected:
        return False
    if not str(signoff.get("evidenceManifestSha256") or "").strip():
        return False
    return all(
        item.get("status") == "APPROVED"
        and str(item.get("approver") or "").strip()
        and str(item.get("approvedAt") or "").strip()
        and isinstance(item.get("evidenceRefs"), list) and item["evidenceRefs"]
        for item in roles
    )


def stage_engineering(document: dict[str, Any]) -> bool:
    return bool_at(
        document,
        ("engineeringPassed",), ("engineeringFullyCompleted",), ("localEngineeringComplete",),
        ("engineering", "fullyCompleted"), ("localEngineering", "passed"), ("ledger", "structurallyComplete"),
    )


def stage_production(document: dict[str, Any]) -> bool:
    return bool_at(
        document,
        ("productionPassed",), ("productionReady",), ("productionComplete",),
        ("production", "ready"), ("productionReadiness", "fullyCompleted"),
        ("productionEvidence", "passed"), ("productionSignoff", "passed"),
    )


def evaluate(
    root: Path,
    policy: dict[str, Any],
    ledger: dict[str, Any],
    signoff: dict[str, Any],
    stage_evidence: dict[str, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    stages = load_stage_evidence(root) if stage_evidence is None else stage_evidence
    stage7 = stages.get("stage7", {})
    stage8 = stages.get("stage8", {})
    stage14 = stages.get("stage14", {})
    stage15 = stages.get("stage15", {})
    stage16 = stages.get("stage16", {})
    stage17 = stages.get("stage17", {})
    stage18 = stages.get("stage18", {})
    stage19 = stages.get("stage19", {})
    stage20 = stages.get("stage20", {})
    stage21 = stages.get("stage21", {})
    stage22 = stages.get("stage22", {})
    stage22_security = stages.get("stage22_security", {})
    stage22_e2e = stages.get("stage22_e2e", {})
    version = (root / "docs/RELEASE_VERSION.txt").read_text(encoding="utf-8").strip()
    open_blockers = [item for item in ledger.get("blockers", []) if item.get("status") != "CLOSED" and item.get("severity") in {"P0", "P1"}]
    ledger_valid = (
        ledger.get("schemaVersion") == "AIMALL_STAGE23_BLOCKER_LEDGER_V1"
        and all(item.get("owner") and item.get("targetDate") and item.get("requiredEvidence") for item in ledger.get("blockers", []))
    )

    controls = {
        "GATE-0": bool(stage8.get("passed")) and bool(stage14.get("structurallyPassed")) and ledger_valid and version == policy.get("releaseVersion"),
        "GATE-1": bool(stage7.get("passed")) and (root / "docs/security/stage22-security-scan-policy.json").exists(),
        "GATE-2": all(stage_engineering(item) for item in (stage15, stage16, stage17, stage18)),
        "GATE-3": bool(stage19.get("engineeringFullyCompleted")),
        "GATE-4": bool(stage20.get("engineeringPassed")),
        "GATE-5": bool(stage21.get("engineeringPassed")),
        "GATE-6": bool(stage22.get("engineeringPassed")) and signoff.get("schemaVersion") == "AIMALL_STAGE23_SIGNOFF_V1",
    }
    evidence = {
        "GATE-0": ledger.get("featureFreeze", {}).get("status") == "CONFIRMED" and not [item for item in open_blockers if item.get("gate") == "GATE-0"],
        "GATE-1": bool(stage22_security.get("passed")) and not [item for item in open_blockers if item.get("gate") == "GATE-1"],
        "GATE-2": all(stage_production(item) for item in (stage15, stage16, stage17, stage18)) and not [item for item in open_blockers if item.get("gate") == "GATE-2"],
        "GATE-3": bool(stage19.get("productionReady")) and not [item for item in open_blockers if item.get("gate") == "GATE-3"],
        "GATE-4": bool(stage20.get("productionPassed")) and not [item for item in open_blockers if item.get("gate") == "GATE-4"],
        "GATE-5": bool(stage21.get("productionPassed")) and not [item for item in open_blockers if item.get("gate") == "GATE-5"],
        "GATE-6": bool(stage22.get("productionPassed")) and bool(stage22_e2e.get("passed")) and signoffs_complete(signoff) and not open_blockers,
    }

    results = []
    passed_so_far = True
    for gate in policy["gates"]:
        gate_id = gate["id"]
        prerequisites_satisfied = passed_so_far
        release_passed = bool(controls[gate_id] and evidence[gate_id] and prerequisites_satisfied)
        gate_blockers = [item["id"] for item in open_blockers if item.get("gate") == gate_id]
        if not prerequisites_satisfied:
            gate_blockers.insert(0, "PREREQUISITE_GATE_BLOCKED")
        results.append({
            "id": gate_id, "name": gate["name"], "controlImplemented": controls[gate_id],
            "evidenceSatisfied": evidence[gate_id], "prerequisitesSatisfied": prerequisites_satisfied,
            "releasePassed": release_passed, "blockers": gate_blockers,
        })
        passed_so_far = passed_so_far and release_passed

    controls_passed = sum(1 for item in results if item["controlImplemented"])
    release_passed = sum(1 for item in results if item["releasePassed"])
    decision = "GO" if release_passed == 7 and not open_blockers and signoffs_complete(signoff) else "NO_GO"
    manifest_payload = {
        "releaseVersion": version,
        "gateResults": results,
        "openBlockerIds": [item["id"] for item in open_blockers],
        "signoffStatuses": {item["role"]: item["status"] for item in signoff.get("roles", [])},
    }
    manifest_hash = hashlib.sha256(json.dumps(manifest_payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()
    return {
        "schemaVersion": "AIMALL_STAGE23_RELEASE_DECISION_V1",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "releaseVersion": version,
        "controlsPassedCount": controls_passed,
        "controlsPassed": controls_passed == 7,
        "releasePassedCount": release_passed,
        "releasePassed": decision == "GO",
        "decision": decision,
        "evidenceManifestSha256": manifest_hash,
        "openP0P1Count": len(open_blockers),
        "signoffsComplete": signoffs_complete(signoff),
        "gates": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="AIMall Stage 23 ordered release decision gate")
    parser.add_argument("--policy", default="docs/operations/stage23-release-gates.json")
    parser.add_argument("--ledger", default="docs/operations/stage23-release-blockers.json")
    parser.add_argument("--signoff", default="docs/operations/stage23-production-signoff.json")
    parser.add_argument("--output", default=".acceptance/stage23/release-decision.json")
    parser.add_argument("--require-controls", action="store_true")
    parser.add_argument("--require-go", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    resolve = lambda value: Path(value) if Path(value).is_absolute() else root / value
    result = evaluate(root, read_json(root, args.policy), read_json(root, args.ledger), read_json(root, args.signoff))
    output = resolve(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.require_controls and not result["controlsPassed"]:
        return 2
    if args.require_go and not result["releasePassed"]:
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
