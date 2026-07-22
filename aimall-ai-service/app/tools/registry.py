from app.config.settings import settings
from app.schemas.tool_schema import ToolDefinition


TOOL_DEFINITIONS: dict[str, ToolDefinition] = {
    "search_products": ToolDefinition(
        name="search_products",
        description="按关键词、分类、价格区间和库存条件搜索 AIMall 商品，返回前端可展示的商品卡片数据。",
        parameters={
            "type": "object",
            "properties": {
                "keyword": {"type": "string", "description": "商品关键词，例如轻薄本、手机、平板"},
                "categoryId": {"type": "integer", "description": "商品分类 ID，可为空"},
                "minPrice": {"type": "number", "description": "最低价格，可为空"},
                "maxPrice": {"type": "number", "description": "最高价格，可为空"},
                "inStock": {"type": "boolean", "description": "是否只返回有库存商品", "default": True},
                "limit": {"type": "integer", "description": "最多返回数量", "default": 10},
            },
            "required": [],
        },
        timeoutSeconds=settings.TOOL_TIMEOUT,
    ),
    "get_product_detail": ToolDefinition(
        name="get_product_detail",
        description="按商品 ID 查询商品详情、价格、库存、图片、卖点、SKU 和描述。",
        parameters={
            "type": "object",
            "properties": {
                "productId": {"type": "integer", "description": "商品 ID"},
            },
            "required": ["productId"],
        },
        timeoutSeconds=settings.TOOL_TIMEOUT,
    ),
    "get_product_skus": ToolDefinition(
        name="get_product_skus",
        description="按商品 ID 查询商品 SKU 列表，包括规格、价格和库存。",
        parameters={
            "type": "object",
            "properties": {
                "productId": {"type": "integer", "description": "商品 ID"},
            },
            "required": ["productId"],
        },
        timeoutSeconds=settings.TOOL_TIMEOUT,
    ),
    "compare_products": ToolDefinition(
        name="compare_products",
        description="按多个商品 ID 查询详情并生成结构化对比数据，用于比较价格、库存、品牌、分类和卖点。",
        parameters={
            "type": "object",
            "properties": {
                "productIds": {
                    "type": "array",
                    "items": {"type": "integer"},
                    "description": "要对比的商品 ID，至少 2 个，最多 5 个",
                },
            },
            "required": ["productIds"],
        },
        timeoutSeconds=settings.TOOL_TIMEOUT,
    ),
    "search_policy_kb": ToolDefinition(
        name="search_policy_kb",
        description="检索商城政策知识库，覆盖售后、退款、退货、配送、优惠券等规则。回答政策问题时必须基于返回的引用来源，查不到资料时应说明没有找到依据。",
        parameters={
            "type": "object",
            "properties": {
                "keyword": {"type": "string", "description": "政策检索关键词或用户问题"},
                "topK": {"type": "integer", "description": "最多返回资料数量", "default": 5},
                "sourceType": {"type": "string", "description": "资料类型，可为空，例如 POLICY 或 FAQ"},
            },
            "required": [],
        },
        timeoutSeconds=settings.RAG_TOOL_TIMEOUT,
    ),
    "list_my_orders": ToolDefinition(
        name="list_my_orders",
        description="查询当前登录用户自己的订单列表。用户身份只能由登录 token 解析，不能由模型传入 userId。",
        parameters={
            "type": "object",
            "properties": {
                "status": {
                    "type": "integer",
                    "description": "订单状态，可为空。0 待支付，1 待发货，2 已发货，3 已完成，4 已关闭，5 无效订单",
                },
                "limit": {"type": "integer", "description": "最多返回数量", "default": 5},
            },
            "required": [],
        },
        risk="MEDIUM",
        requiresAuth=True,
        timeoutSeconds=settings.TOOL_TIMEOUT,
    ),
    "get_my_order_detail": ToolDefinition(
        name="get_my_order_detail",
        description="按订单 ID 或订单号查询当前登录用户自己的订单详情。用户身份只能由登录 token 解析，不能由模型传入 userId。",
        parameters={
            "type": "object",
            "properties": {
                "orderId": {"type": "integer", "description": "订单数据库 ID，可为空"},
                "orderSn": {"type": "string", "description": "用户可见的业务订单号，例如 AIM20260708205327573654，可为空"},
            },
            "required": [],
        },
        risk="MEDIUM",
        requiresAuth=True,
        timeoutSeconds=settings.TOOL_TIMEOUT,
    ),
    "list_my_coupons": ToolDefinition(
        name="list_my_coupons",
        description="查询当前登录用户自己的优惠券列表，只读工具。用户身份只能由登录 token 解析，不能由模型传入 userId。",
        parameters={"type": "object", "properties": {}, "required": []},
        risk="MEDIUM",
        requiresAuth=True,
        timeoutSeconds=settings.TOOL_TIMEOUT,
    ),
    "list_coupon_center": ToolDefinition(
        name="list_coupon_center",
        description="查询当前登录用户可看到的领券中心优惠券列表，只读工具。不会自动领券，只返回可领取、已领取、是否有效等信息。",
        parameters={"type": "object", "properties": {}, "required": []},
        risk="MEDIUM",
        requiresAuth=True,
        timeoutSeconds=settings.TOOL_TIMEOUT,
    ),
    "list_my_returns": ToolDefinition(
        name="list_my_returns",
        description="查询当前登录用户自己的售后/退款申请列表，只读工具。用户身份只能由登录 token 解析。",
        parameters={"type": "object", "properties": {}, "required": []},
        risk="MEDIUM",
        requiresAuth=True,
        timeoutSeconds=settings.TOOL_TIMEOUT,
    ),
    "get_return_detail": ToolDefinition(
        name="get_return_detail",
        description="按售后申请 ID 查询当前登录用户自己的售后详情，只读工具。不能查询其他用户售后。",
        parameters={
            "type": "object",
            "properties": {
                "returnId": {"type": "integer", "description": "售后申请 ID"},
            },
            "required": ["returnId"],
        },
        risk="MEDIUM",
        requiresAuth=True,
        timeoutSeconds=settings.TOOL_TIMEOUT,
    ),
    "list_my_addresses": ToolDefinition(
        name="list_my_addresses",
        description="查询当前登录用户自己的收货地址列表，只读工具。手机号会由后端脱敏，不能修改地址。",
        parameters={"type": "object", "properties": {}, "required": []},
        risk="MEDIUM",
        requiresAuth=True,
        timeoutSeconds=settings.TOOL_TIMEOUT,
    ),
    "add_to_cart_confirmed": ToolDefinition(
        name="add_to_cart_confirmed",
        description="创建加入购物车待确认操作。不会立即修改购物车，必须由用户点击确认后执行。",
        parameters={
            "type": "object",
            "properties": {
                "productId": {"type": "integer", "description": "商品 ID"},
                "productSkuId": {"type": "integer", "description": "可选 SKU ID"},
                "quantity": {"type": "integer", "description": "加入数量，默认 1", "default": 1},
            },
            "required": ["productId"],
        },
        risk="HIGH",
        requiresAuth=True,
        requiresConfirmation=True,
        timeoutSeconds=settings.TOOL_TIMEOUT,
    ),
    "claim_coupon_confirmed": ToolDefinition(
        name="claim_coupon_confirmed",
        description="创建领取优惠券待确认操作。不会立即领券，必须由用户点击确认后执行。",
        parameters={
            "type": "object",
            "properties": {"couponId": {"type": "integer", "description": "优惠券 ID"}},
            "required": ["couponId"],
        },
        risk="HIGH",
        requiresAuth=True,
        requiresConfirmation=True,
        timeoutSeconds=settings.TOOL_TIMEOUT,
    ),
    "cancel_order_confirmed": ToolDefinition(
        name="cancel_order_confirmed",
        description="创建取消本人订单待确认操作。不会立即取消订单，必须由用户点击确认后执行。",
        parameters={
            "type": "object",
            "properties": {
                "orderId": {"type": "integer", "description": "订单数据库 ID，可为空"},
                "orderSn": {"type": "string", "description": "业务订单号，可为空"},
            },
            "required": [],
        },
        risk="HIGH",
        requiresAuth=True,
        requiresConfirmation=True,
        timeoutSeconds=settings.TOOL_TIMEOUT,
    ),
    "apply_return_confirmed": ToolDefinition(
        name="apply_return_confirmed",
        description="创建申请售后待确认操作。不会立即提交售后，必须由用户点击确认后执行。",
        parameters={
            "type": "object",
            "properties": {
                "orderId": {"type": "integer", "description": "订单数据库 ID，可为空"},
                "orderSn": {"type": "string", "description": "业务订单号，可为空"},
                "reason": {"type": "string", "description": "售后原因"},
                "description": {"type": "string", "description": "可选补充说明"},
            },
            "required": ["reason"],
        },
        risk="HIGH",
        requiresAuth=True,
        requiresConfirmation=True,
        timeoutSeconds=settings.TOOL_TIMEOUT,
    ),
}


def list_tool_definitions() -> list[dict]:
    return [tool.model_dump() for tool in TOOL_DEFINITIONS.values()]


def get_tool_definition(name: str) -> ToolDefinition | None:
    return TOOL_DEFINITIONS.get(name)
