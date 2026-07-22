import re


POLICY_KEYWORDS = (
    "规则",
    "政策",
    "平台规则",
    "发货",
    "配送",
    "运费",
    "物流异常",
    "签收",
    "支付",
    "支付超时",
    "重复支付",
    "下单",
    "取消订单",
    "订单取消",
    "购物车",
    "库存",
    "价格",
    "优惠券",
    "满减",
    "退货",
    "退款",
    "换货",
    "售后",
    "保修",
    "发票",
)

PERSONAL_ORDER_KEYWORDS = (
    "我的订单",
    "订单详情",
    "订单状态",
    "订单号",
    "订单编号",
    "查订单",
    "这个订单",
    "该订单",
    "物流单号",
    "快递单号",
)

RECOMMENDATION_KEYWORDS = (
    "推荐",
    "买",
    "选",
    "预算",
    "以内",
    "以下",
    "轻薄本",
    "笔记本",
    "手机",
    "电脑",
    "平板",
    "适合",
    "有没有",
)

COUPON_KEYWORDS = ("我的优惠券", "领券", "领取优惠券", "领取这张券", "券包", "可用券")
RETURN_KEYWORDS = (
    "我的售后", "我的退款", "退款进度", "退货进度", "售后单", "退货单",
    "申请售后", "申请退款", "我要退货", "我要退款",
)
ADDRESS_KEYWORDS = ("我的地址", "收货地址", "默认地址", "配送地址")
COMPARE_KEYWORDS = ("对比", "比较", "哪个更好", "哪款更好", "区别")
ORDER_ACTION_KEYWORDS = ("取消订单", "取消这单", "不要这单")
ORDER_ACTION_POLICY_QUALIFIERS = ("规则", "政策", "条件", "要求", "流程", "怎么", "如何", "为什么", "能否", "是否可以")
PRODUCT_ENTITY_KEYWORDS = ("笔记本", "轻薄本", "手机", "电脑", "平板", "耳机", "显示器", "商品")
PRODUCT_FACT_KEYWORDS = ("价格", "多少钱", "库存", "规格", "配置", "参数", "售价", "现价")


def _contains_any(message: str, keywords: tuple[str, ...]) -> bool:
    return any(keyword in message for keyword in keywords)


def _is_order_action(message: str) -> bool:
    if not _contains_any(message, ORDER_ACTION_KEYWORDS):
        return False
    if re.search(r"\bAIM\d{8,}\b", message, re.IGNORECASE):
        return True
    return not _contains_any(message, ORDER_ACTION_POLICY_QUALIFIERS)


def _is_product_fact_query(message: str) -> bool:
    return _contains_any(message, PRODUCT_ENTITY_KEYWORDS) and _contains_any(message, PRODUCT_FACT_KEYWORDS)


def detect_intent(message: str, page_type: str | None) -> str:
    normalized_message = (message or "").strip()

    if page_type == "PRODUCT_DETAIL":
        return "PRODUCT_QA"
    if page_type == "ORDER_DETAIL":
        return "ORDER_QA"

    if _contains_any(normalized_message, COMPARE_KEYWORDS):
        return "PRODUCT_QA"
    if _contains_any(normalized_message, COUPON_KEYWORDS):
        return "COUPON_QA"
    if _contains_any(normalized_message, RETURN_KEYWORDS):
        return "RETURN_QA"
    if _contains_any(normalized_message, ADDRESS_KEYWORDS):
        return "ADDRESS_QA"
    if _is_order_action(normalized_message):
        return "ORDER_QA"
    if _contains_any(normalized_message, PERSONAL_ORDER_KEYWORDS):
        return "ORDER_QA"
    if _is_product_fact_query(normalized_message):
        return "PRODUCT_QA"
    if _contains_any(normalized_message, POLICY_KEYWORDS):
        return "POLICY_QA"
    if _contains_any(normalized_message, RECOMMENDATION_KEYWORDS):
        return "RECOMMENDATION"
    return "GENERAL_QA"
