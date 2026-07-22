from __future__ import annotations

import argparse
import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def atomic_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(prefix=path.name, suffix=".tmp", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_name, path)
    finally:
        if os.path.exists(temporary_name):
            os.unlink(temporary_name)


def append_audit(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    line = json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n"
    with path.open("a", encoding="utf-8") as handle:
        handle.write(line)
        handle.flush()
        os.fsync(handle.fileno())


def decide(policy: dict[str, Any], metrics: dict[str, Any], tenant: str, user: str, tripped: bool) -> dict[str, Any]:
    missing = [name for name in policy["requiredMetricFields"] if name not in metrics]
    violations = [f"MISSING_METRIC:{name}" for name in missing]
    for name, threshold in policy["thresholds"].items():
        if name in metrics and float(metrics[name]) > float(threshold):
            violations.append(f"THRESHOLD:{name}:{metrics[name]}>{threshold}")
    if tripped:
        violations.insert(0, "PERSISTED_TRIP")

    state = str(policy.get("state") or "CLOSED").upper()
    tenant_match = tenant in {str(value) for value in policy.get("tenantAllowlist", [])}
    user_match = user in {str(value) for value in policy.get("userAllowlist", [])}
    if state == "FULL":
        allowlisted = True
    elif policy.get("requireTenantAndUserMatch", True):
        allowlisted = tenant_match and user_match
    else:
        allowlisted = tenant_match or user_match
    enabled = state in {"CANARY", "FULL"} and allowlisted and not violations
    return {
        "enabled": enabled,
        "decision": "ALLOW" if enabled else "CLOSED",
        "policyState": state,
        "tenantMatch": tenant_match,
        "userMatch": user_match,
        "violations": violations,
        "tripRequired": bool(violations and any(item != "PERSISTED_TRIP" for item in violations)),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="AIMall Stage 21 fail-closed canary gate")
    parser.add_argument("--policy", default="docs/operations/canary-release-policy.json")
    parser.add_argument("--metrics", required=True)
    parser.add_argument("--tenant", required=True)
    parser.add_argument("--user", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--state-file", default=".acceptance/stage21/canary-state.json")
    parser.add_argument("--audit-file", default=".acceptance/stage21/canary-audit.jsonl")
    parser.add_argument("--reset-trip", action="store_true")
    parser.add_argument("--change-id")
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    resolve = lambda value: Path(value) if Path(value).is_absolute() else root / value
    policy = json.loads(resolve(args.policy).read_text(encoding="utf-8"))
    metrics = json.loads(resolve(args.metrics).read_text(encoding="utf-8"))
    state_path = resolve(args.state_file)
    audit_path = resolve(args.audit_file)
    existing = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {}
    tripped = existing.get("state") == "TRIPPED"
    if args.reset_trip:
        if not args.change_id:
            raise SystemExit("--reset-trip requires --change-id")
        tripped = False
        atomic_json(state_path, {"state": "RESET", "changeId": args.change_id, "resetAt": datetime.now(timezone.utc).isoformat()})

    decision = decide(policy, metrics, args.tenant, args.user, tripped)
    now = datetime.now(timezone.utc).isoformat()
    result = {
        "schemaVersion": "AIMALL_CANARY_DECISION_V1",
        "generatedAt": now,
        "feature": policy["feature"],
        "tenant": args.tenant,
        "user": args.user,
        "metrics": metrics,
        **decision,
    }
    if decision["tripRequired"]:
        atomic_json(state_path, {"state": "TRIPPED", "trippedAt": now, "violations": decision["violations"]})
    append_audit(audit_path, result)
    atomic_json(resolve(args.output), result)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["enabled"] else 3


if __name__ == "__main__":
    raise SystemExit(main())
