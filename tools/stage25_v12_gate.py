from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


EXPECTED_IDS = [f"S25-{index:02d}" for index in range(1, 10)]
EXPECTED_AI_MODES = {"MOCK", "RULE_BASED", "LLM", "SANDBOX", "PRODUCTION"}


def read_text(root: Path, relative: str) -> str:
    return (root / relative).read_text(encoding="utf-8-sig")


def read_json(root: Path, relative: str) -> dict[str, Any]:
    return json.loads(read_text(root, relative))


def production_verified(item: dict[str, Any]) -> bool:
    return (
        item.get("status") == "VERIFIED"
        and bool(str(item.get("owner") or "").strip())
        and bool(str(item.get("approver") or "").strip())
        and bool(str(item.get("verifiedAt") or "").strip())
        and isinstance(item.get("evidenceRefs"), list)
        and bool(item["evidenceRefs"])
    )


def _owner_registry_valid(registry: dict[str, Any]) -> bool:
    entries = registry.get("entries") or []
    required_ids = ["PAY-001", "INV-001", "TENANT-001", "RAG-001", "DR-001"]
    required_fields = {
        "id", "ownerRole", "ownerName", "backup", "approver", "dueDate", "dependency",
        "status", "evidenceRefs", "blocker", "updatedAt",
    }
    return (
        registry.get("schemaVersion") == "AIMALL_STAGE25_OWNER_REGISTRY_V1"
        and [entry.get("id") for entry in entries] == required_ids
        and all(required_fields <= set(entry) for entry in entries)
    )


def evaluate(root: Path, policy: dict[str, Any], production: dict[str, Any]) -> dict[str, Any]:
    money = read_text(root, "aimall-server/src/main/java/com/aimall/server/money/MoneyPolicy.java")
    money_migration = read_text(root, "aimall-server/src/main/resources/db/migration/V20260721_1800__precise_money_snapshots.sql")
    money_contract = read_json(root, "docs/operations/stage25-money-migration.json")
    settings = read_text(root, "aimall-ai-service/app/config/settings.py")
    outbox_mapper = read_text(root, "aimall-server/src/main/java/com/aimall/server/mapper/OutboxEventMapper.java")
    outbox_claim = read_text(root, "aimall-server/src/main/java/com/aimall/server/service/impl/OutboxClaimService.java")
    outbox_worker = read_text(root, "aimall-server/src/main/java/com/aimall/server/service/impl/OutboxWorker.java")
    outbox_service = read_text(root, "aimall-server/src/main/java/com/aimall/server/service/impl/OutboxEventService.java")
    shipment = read_text(root, "aimall-server/src/main/java/com/aimall/server/service/impl/ShipmentEligibilityService.java")
    logistics = read_text(root, "aimall-server/src/main/java/com/aimall/server/service/impl/LogisticsServiceImpl.java")
    admin_service = read_text(root, "aimall-server/src/main/java/com/aimall/server/service/AdminService.java")
    item_mapper = read_text(root, "aimall-server/src/main/java/com/aimall/server/mapper/OmsOrderItemMapper.java")
    schema = read_text(root, "aimall-server/src/main/resources/schema.sql")
    java_tenant = read_text(root, "aimall-server/src/main/java/com/aimall/server/tenant/TenantPolicy.java")
    ai_schema = read_text(root, "aimall-ai-service/app/schemas/chat_schema.py")
    milvus = read_text(root, "aimall-ai-service/app/rag/milvus_store.py")
    java_security = read_text(root, "aimall-server/src/main/java/com/aimall/server/config/SaTokenConfigure.java")
    java_health = read_text(root, "aimall-server/src/main/java/com/aimall/server/health/HealthController.java")
    ai_main = read_text(root, "aimall-ai-service/main.py")
    ai_health = read_text(root, "aimall-ai-service/app/api/health_api.py")
    manifest = read_json(root, "aimall-ai-service/data/evaluation/evalset-v1-manifest.json")
    case_path = root / "aimall-ai-service/data/evaluation/evalset-v1.jsonl"
    cases = case_path.read_text(encoding="utf-8-sig")
    governance = read_json(root, "docs/operations/rag-evalset-governance.json")
    owner_registry = read_json(root, "docs/operations/stage25-owner-registry.json")
    migration_governance = read_json(root, "docs/operations/migration-governance.json")
    capacity = read_json(root, "docs/operations/capacity-gates.json")
    dr = read_text(root, "docs/operations/disaster-recovery.md")
    performance = read_text(root, "docs/operations/performance-capacity-release.md")
    canary = read_json(root, "docs/operations/canary-release-policy.json")

    case_hash = hashlib.sha256(case_path.read_bytes()).hexdigest()
    checks = {
        "S25-01": (
            "STORAGE_SCALE = 4" in money
            and 'DEFAULT_CURRENCY = "CNY"' in money
            and "last allocation absorbs" not in money.lower()
            and "index == weights.size() - 1" in money
            and "decimal(18,4)" in money_migration.lower()
            and money_contract.get("currentPhase") == "EXPAND_DUAL_WRITE"
            and len(money_contract.get("contractEntryConditions") or []) == 4
        ),
        "S25-02": (
            all(f'"{mode}"' in settings for mode in EXPECTED_AI_MODES)
            and 'AI_RUNTIME_MODES = {"MOCK", "RULE_BASED", "LLM", "SANDBOX", "PRODUCTION"}' in settings
            and 'settings.AI_RUNTIME_MODE != "PRODUCTION"' in settings
            and "validate_runtime_settings()" in settings
            and "AI_RUNTIME_MODE_CHANGE_ID is required in production" in settings
        ),
        "S25-03": (
            "FOR UPDATE SKIP LOCKED" in outbox_mapper
            and "@Transactional" in outbox_claim
            and "listClaimCandidatesForUpdate" in outbox_claim
            and "renewLease" in outbox_worker
            and "Math.min(45L" in outbox_worker
            and all(f"'{state}'" in outbox_mapper for state in ("PENDING", "PROCESSING", "SUCCEEDED", "RETRY_WAIT", "DEAD_LETTER"))
            and "MANUAL_REVIEW" in outbox_mapper
            and "moveToManualReview" in outbox_mapper
            and "/manual-review" in read_text(root, "aimall-server/src/main/java/com/aimall/server/admin/AdminPaymentOperationsController.java")
            and "payload_hash" in outbox_mapper
            and "last_lease_owner" in outbox_mapper
            and "setPayloadHash" in outbox_service
        ),
        "S25-04": (
            "class ShipmentEligibilityService" in shipment
            and '"PAID".equals(payment.getPaymentState())' in shipment
            and "payment.getPaidAmount(), order.getPayAmount()" in shipment
            and "getRefundedAmount()).signum() != 0" in shipment
            and "getReturnReservedQuantity" in shipment
            and "getRefundedQuantity" in shipment
            and "eligibilityService.assertEligible" in logistics
            and "addShippedQuantity" in logistics
            and "product_quantity - shipped_quantity - return_reserved_quantity - refunded_quantity" in item_mapper
            and "uk_shipment_tracking" in schema
            and "shipOrder(" not in admin_service
        ),
        "S25-05": (
            'SINGLE_TENANT = "SINGLE_TENANT"' in java_tenant
            and "MULTI_TENANT 尚未完成数据库强制约束" in java_tenant
            and 'settings.TENANT_MODE != "SINGLE_TENANT"' in settings
            and "non-default tenantId is forbidden" in ai_schema
            and '"tenant_id"' in milvus
            and "setTenantId(defaultTenantId)" in outbox_service
        ),
        "S25-06": (
            '"/api/health/liveness"' in java_security
            and 'addPathPatterns("/internal/ai/**", "/api/health/startup", "/api/health/readiness/**")' in java_security
            and all(path in java_health for path in ("/health/readiness/core", "/health/readiness/ai", "/health/readiness/payment"))
            and '@RequestMapping("/api")' in java_health
            and '"/admin/health"' in java_health
            and "app.include_router(public_health_router)" in ai_main
            and "app.include_router(health_router, dependencies=internal_dependencies)" in ai_main
            and "/health/liveness" in ai_health
            and "/health/readiness/ai" in ai_health
        ),
        "S25-07": (
            manifest.get("caseFileSha256") == case_hash
            and governance.get("caseFileSha256") == case_hash
            and manifest.get("expectedCaseCount") == 15
            and manifest.get("releaseSeeds") == [25001, 25002, 25003]
            and all(case_id in cases for case_id in (
                "AIM-EVAL-POLICY_RAG-013",
                "AIM-EVAL-POLICY_RAG-014",
                "AIM-EVAL-GUARDRAIL-015",
            ))
            and governance.get("unsupportedAnswerAnnotation")
            == "two independent human labels; disagreement requires adjudicator"
            and governance.get("onlineSamplesCanReplaceOfflineGate") is False
        ),
        "S25-08": (
            migration_governance.get("schemaVersion")
            and capacity.get("schemaVersion")
            and canary.get("schemaVersion")
            and "binlog" in dr.lower()
            and "aof" in dr.lower()
            and "milvus" in dr.lower()
            and int((capacity.get("releaseSoakGate") or {}).get("minimumDurationSeconds", 0)) >= 3600
            and "3,600 seconds" in performance
            and "p99" in performance.lower()
        ),
        "S25-09": _owner_registry_valid(owner_registry),
    }

    configured = policy.get("controls") or []
    configured_ids = [item.get("id") for item in configured]
    policy_valid = (
        policy.get("schemaVersion") == "AIMALL_STAGE25_V12_CONTROLS_V1"
        and configured_ids == EXPECTED_IDS
        and all(item.get("section") and item.get("ownerRole") and item.get("requiredEvidence") for item in configured)
    )
    production_items = production.get("evidence") or []
    production_valid = (
        production.get("schemaVersion") == "AIMALL_STAGE25_PRODUCTION_EVIDENCE_V1"
        and production.get("environment") == "production"
        and [item.get("id") for item in production_items] == EXPECTED_IDS
        and all(item.get("status") in {"VERIFIED", "UNVERIFIED"} and "blocker" in item for item in production_items)
    )
    control_results = [
        {"id": item["id"], "section": item["section"], "name": item["name"], "implemented": bool(checks.get(item["id"]))}
        for item in configured
    ]
    production_results = [
        {"id": item["id"], "verified": production_verified(item), "status": item["status"], "blocker": item.get("blocker", "")}
        for item in production_items
    ]
    engineering_count = sum(item["implemented"] for item in control_results)
    production_count = sum(item["verified"] for item in production_results)
    engineering_passed = policy_valid and production_valid and engineering_count == len(EXPECTED_IDS)
    production_passed = engineering_passed and production_count == len(EXPECTED_IDS)
    stable_manifest = {
        "controls": {item["id"]: item["implemented"] for item in control_results},
        "production": {item["id"]: item["verified"] for item in production_results},
        "evalsetHash": case_hash,
    }
    return {
        "schemaVersion": "AIMALL_STAGE25_V12_GATE_V1",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "engineeringPassedCount": engineering_count,
        "engineeringTotal": len(EXPECTED_IDS),
        "engineeringPassed": engineering_passed,
        "productionPassedCount": production_count,
        "productionTotal": len(EXPECTED_IDS),
        "productionPassed": production_passed,
        "decision": "READY" if production_passed else "NOT_PRODUCTION_READY",
        "evidenceManifestSha256": hashlib.sha256(
            json.dumps(stable_manifest, sort_keys=True, separators=(",", ":")).encode()
        ).hexdigest(),
        "controls": control_results,
        "productionEvidence": production_results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="AIMall Stage 25 v1.2 execution-constraint gate")
    parser.add_argument("--policy", default="docs/operations/stage25-controls.json")
    parser.add_argument("--production", default="docs/operations/stage25-production-evidence.json")
    parser.add_argument("--output", default=".acceptance/stage25/v12-gate.json")
    parser.add_argument("--require-engineering", action="store_true")
    parser.add_argument("--require-production", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    result = evaluate(root, read_json(root, args.policy), read_json(root, args.production))
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
