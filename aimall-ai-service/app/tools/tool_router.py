from app.schemas.chat_schema import PageContext
from app.tools.registry import get_tool_definition


TOOL_GROUPS: dict[str, tuple[str, ...]] = {
    "product": ("search_products", "get_product_detail", "get_product_skus", "compare_products", "add_to_cart_confirmed"),
    "order": ("list_my_orders", "get_my_order_detail", "cancel_order_confirmed", "apply_return_confirmed"),
    "coupon": ("list_my_coupons", "list_coupon_center", "claim_coupon_confirmed"),
    "return": ("list_my_returns", "get_return_detail", "apply_return_confirmed"),
    "address": ("list_my_addresses",),
    "policy": ("search_policy_kb",),
}

INTENT_TOOL_GROUPS: dict[str, tuple[str, ...]] = {
    "RECOMMENDATION": ("product",),
    "PRODUCT_QA": ("product", "policy"),
    "ORDER_QA": ("order", "policy"),
    "COUPON_QA": ("coupon",),
    "RETURN_QA": ("return", "policy"),
    "ADDRESS_QA": ("address",),
    "POLICY_QA": ("policy",),
    "GENERAL_QA": ("product", "policy"),
}


def select_candidate_tool_names(intent: str, page_context: PageContext | None = None) -> list[str]:
    names: list[str] = []
    for group in INTENT_TOOL_GROUPS.get(intent, ("product", "policy")):
        names.extend(TOOL_GROUPS[group])

    if page_context and page_context.pageType == "PRODUCT_DETAIL":
        names.extend(("get_product_detail", "get_product_skus", "add_to_cart_confirmed"))
    if page_context and page_context.pageType == "ORDER_DETAIL":
        names.extend(("get_my_order_detail", "list_my_orders", "cancel_order_confirmed", "apply_return_confirmed"))

    return _dedupe_existing(names)


def list_candidate_tool_definitions(intent: str, page_context: PageContext | None = None) -> list[dict]:
    return [
        tool.model_dump()
        for name in select_candidate_tool_names(intent, page_context)
        if (tool := get_tool_definition(name)) is not None
    ]


def build_tool_routing_info(intent: str, page_context: PageContext | None = None) -> dict:
    candidate_tools = select_candidate_tool_names(intent, page_context)
    return {
        "strategy": "intent_page_rule_v1",
        "intent": intent,
        "pageType": page_context.pageType if page_context else None,
        "candidateTools": candidate_tools,
    }


def _dedupe_existing(names: list[str]) -> list[str]:
    result: list[str] = []
    for name in names:
        if name in result or get_tool_definition(name) is None:
            continue
        result.append(name)
    return result
