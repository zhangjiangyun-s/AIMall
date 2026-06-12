def detect_intent(message: str, page_type: str | None) -> str:
    if "退货" in message or "退款" in message:
        return "POLICY_QA"
    if "推荐" in message:
        return "RECOMMENDATION"
    if page_type == "PRODUCT_DETAIL":
        return "PRODUCT_QA"
    if page_type == "ORDER_DETAIL":
        return "ORDER_QA"
    return "GENERAL_QA"
