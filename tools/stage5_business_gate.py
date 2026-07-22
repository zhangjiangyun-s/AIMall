from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


EXPECTED_IDS = [f"P5-{index:02d}" for index in range(1, 9)]


def read_text(root: Path, relative: str) -> str:
    return (root / relative).read_text(encoding="utf-8-sig")


def read_json(root: Path, relative: str) -> dict[str, Any]:
    return json.loads(read_text(root, relative))


def evaluate(root: Path, policy: dict[str, Any]) -> dict[str, Any]:
    product = read_text(root, "aimall-server/src/main/java/com/aimall/server/product/ProductController.java")
    product_service = read_text(root, "aimall-server/src/main/java/com/aimall/server/service/impl/ProductServiceImpl.java")
    interaction = read_text(root, "aimall-server/src/main/java/com/aimall/server/service/impl/ProductInteractionService.java")
    interaction_controller = read_text(root, "aimall-server/src/main/java/com/aimall/server/user/ProductInteractionController.java")
    metadata_controller = read_text(root, "aimall-server/src/main/java/com/aimall/server/admin/AdminProductMetadataController.java")
    metadata_service = read_text(root, "aimall-server/src/main/java/com/aimall/server/service/impl/ProductMetadataService.java")
    price_service = read_text(root, "aimall-server/src/main/java/com/aimall/server/service/impl/ProductPriceRuleService.java")
    admin_operations = read_text(root, "aimall-server/src/main/java/com/aimall/server/admin/AdminProductOperationsController.java")
    return_apply = read_text(root, "aimall-server/src/main/java/com/aimall/server/service/impl/ReturnApplyServiceImpl.java")
    workflow = read_text(root, "aimall-server/src/main/java/com/aimall/server/service/impl/ReturnWorkflowService.java")
    return_user = read_text(root, "aimall-server/src/main/java/com/aimall/server/user/ReturnApplyController.java")
    return_admin = read_text(root, "aimall-server/src/main/java/com/aimall/server/admin/AdminReturnController.java")
    account = read_text(root, "aimall-server/src/main/java/com/aimall/server/service/impl/AccountSecurityService.java")
    user = read_text(root, "aimall-server/src/main/java/com/aimall/server/user/UserController.java")
    register_dto = read_text(root, "aimall-server/src/main/java/com/aimall/server/user/dto/MemberRegisterRequest.java")
    reset_dto = read_text(root, "aimall-server/src/main/java/com/aimall/server/user/dto/PasswordResetRequest.java")
    change_dto = read_text(root, "aimall-server/src/main/java/com/aimall/server/user/dto/PasswordChangeRequest.java")
    schema = read_text(root, "aimall-server/src/main/resources/schema.sql")
    web_product = read_text(root, "aimall-web/src/views/ProductDetailView.vue")
    web_interaction_api = read_text(root, "aimall-web/src/api/productInteractionApi.ts")
    web_account = read_text(root, "aimall-web/src/views/AccountView.vue")
    web_user_api = read_text(root, "aimall-web/src/api/userApi.ts")
    web_return = read_text(root, "aimall-web/src/views/ReturnDetailView.vue")
    web_return_api = read_text(root, "aimall-web/src/api/returnApi.ts")
    admin_return = read_text(root, "aimall-admin/src/views/ReturnManageView.vue")
    admin_return_api = read_text(root, "aimall-admin/src/api/returnAdminApi.ts")

    checks = {
        "P5-01": (
            "getPublishStatus" in product and "getVerifyStatus" in product
            and "ResourceNotFoundException" in product
            and "countAllByProductId" in product_service
            and "aggregateAvailableStock" in product_service
            and "PmsSkuStock::getStatus" in product_service
            and "RESOURCE_NOT_FOUND" in read_text(root, "aimall-server/src/main/java/com/aimall/server/exception/ResourceNotFoundException.java")
        ),
        "P5-02": (
            "/brands" in metadata_controller and "/attribute-templates" in metadata_controller
            and "validateSchema" in metadata_service and "getSchemaJson" in metadata_service
            and "/images" in read_text(root, "aimall-server/src/main/java/com/aimall/server/admin/AdminProductCatalogController.java")
            and "spData" in read_text(root, "aimall-server/src/main/java/com/aimall/server/entity/PmsSkuStock.java")
        ),
        "P5-03": (
            "price-history" in admin_operations and "price-rules" in admin_operations
            and "getMemberLevel" in price_service and "getPerMemberLimit" in price_service
            and "sumPurchasedWithoutSku" in price_service and "sumPurchasedWithSku" in price_service
            and "getStartTime" in price_service and "getEndTime" in price_service
        ),
        "P5-04": (
            "PageResult" in product_service and "pageProducts" in product_service
            and "aggregateAvailableStock" in product_service and "inStock" in product_service
            and "normalizeSort" in product_service and "stock-alerts" in admin_operations
        ),
        "P5-05": (
            "completed orders" not in interaction.lower()
            and "canReview" in interaction and "favorite" in interaction_controller
            and "browse-history" in interaction_controller and "recommendations" in interaction_controller
            and 'x.put("reason"' in interaction
            and "uk_review_order_item" in schema
            and all(term in web_product + web_interaction_api for term in ("favoriteProduct", "recordProductBrowse", "submitProductReview", "reviews"))
            and all(term in web_interaction_api + web_account for term in ("fetchFavorites", "fetchBrowseHistory", "fetchRecommendations"))
        ),
        "P5-06": (
            "returnAmount" in return_apply and "ReturnItemRequest" in return_apply
            and "OmsReturnEvidenceMapper" in workflow
            and "mediaType" in workflow and "IMAGE" in workflow and "VIDEO" in workflow
            and "statusEvents" in return_user and "statusEvents" in return_admin
            and "handleNote" in return_admin
            and all(name in return_user + return_admin for name in ("APPLIED", "APPROVED", "REJECTED", "RETURNING", "RECEIVED", "REFUNDING", "REFUNDED", "CLOSED"))
            and all(term in web_return + web_return_api + admin_return + admin_return_api for term in ("evidence", "inspection", "returnTrackingNo", "statusEvents"))
        ),
        "P5-07": (
            "submitLogistics" in workflow and "returnTrackingNo" in return_user + return_admin
            and "inspect" in workflow and "inspectionResult" in return_user + return_admin
            and "slaDeadline" in return_user + return_admin and "markOverdue" in workflow
            and "remainingAmount" in return_apply and "completedRefundAmount" in return_apply
        ),
        "P5-08": (
            "password/reset" in user and "password/change" in user
            and "security/login-history" in user and "security/devices" in user
            and "/privacy/consent" in user and "/freeze" in user and "/cancel" in user
            and "validatePassword" in account and "LOGIN_ACCOUNT_IP_LIMIT" in account
            and "LOGIN_DEVICE_LIMIT" in account and "checkRegistrationAllowed" in account
            and "checkPasswordResetAllowed" in account
            and "\\\\d{6}" in register_dto and "\\\\d{6}" in reset_dto
            and "@Size(min = 12" in register_dto + reset_dto + change_dto
            and all(term in web_account + web_user_api for term in ("changePassword", "fetchLoginHistory", "fetchSecurityDevices", "freezeAccount", "cancelAccount"))
        ),
    }
    controls = policy.get("controls") or []
    policy_valid = (
        policy.get("schemaVersion") == "AIMALL_STAGE5_BUSINESS_COMPLETION_CONTROLS_V1"
        and [item.get("id") for item in controls] == EXPECTED_IDS
        and all(item.get("section") and item.get("requiredEvidence") for item in controls)
    )
    results = [{"id": item["id"], "section": item["section"], "name": item["name"], "implemented": bool(checks.get(item["id"]))} for item in controls]
    passed = sum(item["implemented"] for item in results)
    return {
        "schemaVersion": "AIMALL_STAGE5_BUSINESS_GATE_V1",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "engineeringPassedCount": passed,
        "engineeringTotal": len(EXPECTED_IDS),
        "engineeringPassed": policy_valid and passed == len(EXPECTED_IDS),
        "productionPassed": False,
        "productionStatus": "NOT_PRODUCTION_READY",
        "controls": results,
        "productionBlocker": "Stage 5 local engineering controls cannot replace production email/provider, storage scanning and real operational acceptance evidence.",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--policy", default="docs/operations/stage5-controls.json")
    parser.add_argument("--output", default=".acceptance/stage5/business-gate.json")
    parser.add_argument("--require-engineering", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    result = evaluate(root, read_json(root, args.policy))
    output = root / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if not args.require_engineering or result["engineeringPassed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
