from fastapi import APIRouter

from app.schemas.chat_schema import ChatRequest, ChatResponse
from app.router.intent_router import detect_intent

router = APIRouter(prefix="/ai", tags=["Chat"])

INTENT_ANSWERS = {
    "POLICY_QA": "根据平台规则，退货和退款问题需要以后台维护的规则文档为准。本轮为 mock 回复，后续会接入知识库。",
    "RECOMMENDATION": "根据你的需求，本轮 mock 推荐优先查看：学习平板 A1、轻薄笔记本 B2。后续会接入商品搜索工具。",
    "PRODUCT_QA": "这个商品适合学习、办公等轻量场景。本轮为商品问答 mock，后续会读取商品详情。",
    "ORDER_QA": "我会根据你的订单状态进行解释。本轮为订单问答 mock，后续会通过 Java 后端查询当前用户订单。",
    "GENERAL_QA": "我是 AIMall AI 助手，可以帮助你了解商品、订单和平台规则。本轮为通用 mock 回复。",
}


@router.post("/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):
    page_type = request.pageContext.pageType if request.pageContext else None
    intent = detect_intent(request.message, page_type)
    answer = INTENT_ANSWERS.get(intent, INTENT_ANSWERS["GENERAL_QA"])

    related_products = []
    suggested_actions = []

    if intent == "RECOMMENDATION":
        related_products = [
            {"productId": 1001, "name": "学习平板 A1", "price": 2999},
            {"productId": 1002, "name": "轻薄笔记本 B2", "price": 3999},
        ]
        suggested_actions = [
            {"type": "VIEW_PRODUCT", "label": "查看学习平板 A1", "productId": 1001},
            {"type": "VIEW_PRODUCT", "label": "查看轻薄笔记本 B2", "productId": 1002},
        ]
    elif intent == "PRODUCT_QA" and request.pageContext and request.pageContext.productId:
        related_products = [
            {"productId": request.pageContext.productId, "name": "当前商品 mock", "price": 0},
        ]

    return ChatResponse(
        answer=answer,
        intent=intent,
        relatedProducts=related_products,
        suggestedActions=suggested_actions,
        toolCalls=[],
    )
