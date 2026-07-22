from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def read_json(root: Path, relative: str) -> dict[str, Any]:
    return json.loads((root / relative).read_text(encoding="utf-8-sig"))


def read_text(root: Path, relative: str) -> str:
    return (root / relative).read_text(encoding="utf-8-sig")


def decision_complete(item: dict[str, Any]) -> bool:
    return (
        item.get("status") == "RESOLVED"
        and bool(str(item.get("decision") or "").strip())
        and bool(str(item.get("owner") or "").strip())
        and bool(str(item.get("approver") or "").strip())
        and isinstance(item.get("evidenceRefs"), list)
        and bool(item["evidenceRefs"])
    )


def evaluate(
    root: Path,
    policy: dict[str, Any],
    decisions: dict[str, Any],
    stage23_result: dict[str, Any] | None = None,
) -> dict[str, Any]:
    outbox_service = read_text(root, "aimall-server/src/main/java/com/aimall/server/service/impl/OutboxEventService.java")
    outbox_worker = read_text(root, "aimall-server/src/main/java/com/aimall/server/service/impl/OutboxWorker.java")
    outbox_mapper = read_text(root, "aimall-server/src/main/java/com/aimall/server/mapper/OutboxEventMapper.java")
    order_service = read_text(root, "aimall-server/src/main/java/com/aimall/server/service/impl/OrderServiceImpl.java")
    java_trace = read_text(root, "aimall-server/src/main/java/com/aimall/server/config/RequestTraceFilter.java")
    ai_trace = read_text(root, "aimall-ai-service/app/observability/middleware.py")
    ai_trace_context = read_text(root, "aimall-ai-service/app/observability/trace_context.py")
    ai_auth = read_text(root, "aimall-ai-service/app/security/internal_auth.py")
    java_tenant = read_text(root, "aimall-server/src/main/java/com/aimall/server/tenant/TenantPolicy.java")
    ai_settings = read_text(root, "aimall-ai-service/app/config/settings.py")
    ai_schema = read_text(root, "aimall-ai-service/app/schemas/chat_schema.py")
    milvus = read_text(root, "aimall-ai-service/app/rag/milvus_store.py")
    if stage23_result is None:
        try:
            stage23 = read_json(root, ".acceptance/stage23/release-decision.json")
        except FileNotFoundError:
            stage23 = {}
    else:
        stage23 = stage23_result

    checks = {
        "R24-01": (
            "insertIgnore(event)" in outbox_service
            and "@Transactional" in order_service
            and "outboxEventService.enqueue(" in order_service
            and "lease_until" in outbox_mapper
            and "int claim(" in outbox_mapper
            and "markRetry" in outbox_worker
            and "markDeadLetter" in outbox_worker
        ),
        "R24-02": (
            "UUID.randomUUID().toString()" in java_trace
            and 'MDC.put("clientTraceId"' in java_trace
            and "new_trace_id()" in ai_trace
            and "resolve_client_trace_id(candidate)" in ai_trace
            and "current_client_trace_id" in ai_trace_context
            and "hmac.compare_digest" in ai_auth
        ),
        "R24-03": (
            'SINGLE_TENANT = "SINGLE_TENANT"' in java_tenant
            and "MULTI_TENANT 尚未完成数据库强制约束" in java_tenant
            and 'settings.TENANT_MODE != "SINGLE_TENANT"' in ai_settings
            and "non-default tenantId is forbidden" in ai_schema
            and '"tenant_id"' in milvus
            and "event.setTenantId(defaultTenantId)" in outbox_service
        ),
        "R24-04": (
            stage23.get("controlsPassed") is True
            and stage23.get("releasePassed") is False
            and stage23.get("decision") == "NO_GO"
            and int(stage23.get("controlsPassedCount", 0)) == 7
            and int(stage23.get("releasePassedCount", -1)) == 0
        ),
    }

    configured_ids = [item.get("id") for item in policy.get("controls", [])]
    expected_ids = ["R24-01", "R24-02", "R24-03", "R24-04"]
    policy_valid = (
        policy.get("schemaVersion") == "AIMALL_STAGE24_REFLECTION_CONTROLS_V1"
        and configured_ids == expected_ids
        and all(item.get("owner") and item.get("requiredEvidence") for item in policy.get("controls", []))
    )
    decision_items = decisions.get("decisions", [])
    decisions_valid = (
        decisions.get("schemaVersion") == "AIMALL_STAGE24_EXTERNAL_DECISIONS_V1"
        and [item.get("id") for item in decision_items] == [f"D24-0{index}" for index in range(1, 6)]
        and all(item.get("owner") and item.get("status") in {"RESOLVED", "UNRESOLVED"} for item in decision_items)
    )
    control_results = [
        {
            "id": item["id"],
            "name": item["name"],
            "implemented": bool(checks.get(item["id"])),
            "owner": item["owner"],
        }
        for item in policy.get("controls", [])
    ]
    decision_results = [
        {
            "id": item["id"],
            "name": item["name"],
            "resolved": decision_complete(item),
            "status": item["status"],
            "owner": item["owner"],
            "blocker": item.get("blocker", ""),
        }
        for item in decision_items
    ]
    engineering_count = sum(1 for item in control_results if item["implemented"])
    production_count = sum(1 for item in decision_results if item["resolved"])
    engineering_complete = policy_valid and decisions_valid and engineering_count == 4
    production_ready = engineering_complete and production_count == 5
    manifest = {
        "controls": {item["id"]: item["implemented"] for item in control_results},
        "decisions": {item["id"]: item["resolved"] for item in decision_results},
        "stage23Decision": stage23.get("decision"),
    }
    return {
        "schemaVersion": "AIMALL_STAGE24_REFLECTION_GATE_V1",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "engineeringPassedCount": engineering_count,
        "engineeringTotal": 4,
        "engineeringPassed": engineering_complete,
        "productionPassedCount": production_count,
        "productionTotal": 5,
        "productionPassed": production_ready,
        "decision": "READY" if production_ready else "NOT_PRODUCTION_READY",
        "evidenceManifestSha256": hashlib.sha256(
            json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest(),
        "controls": control_results,
        "externalDecisions": decision_results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="AIMall Stage 24 reflection boundary gate")
    parser.add_argument("--policy", default="docs/operations/stage24-reflection-controls.json")
    parser.add_argument("--decisions", default="docs/operations/stage24-external-decisions.json")
    parser.add_argument("--output", default=".acceptance/stage24/reflection-gate.json")
    parser.add_argument("--require-engineering", action="store_true")
    parser.add_argument("--require-production", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    result = evaluate(root, read_json(root, args.policy), read_json(root, args.decisions))
    output = Path(args.output) if Path(args.output).is_absolute() else root / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.require_engineering and not result["engineeringPassed"]:
        return 2
    if args.require_production and not result["productionPassed"]:
        return 3
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
