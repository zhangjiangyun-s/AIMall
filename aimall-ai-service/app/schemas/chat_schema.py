from pydantic import BaseModel
from typing import Optional, Any


class PageContext(BaseModel):
    pageType: Optional[str] = None
    productId: Optional[int] = None
    orderId: Optional[int] = None


class AuthContext(BaseModel):
    token: Optional[str] = None


class ChatRequest(BaseModel):
    userId: int
    message: str
    sessionId: str
    pageContext: Optional[PageContext] = None
    authContext: Optional[AuthContext] = None


class ChatResponse(BaseModel):
    answer: str
    intent: str
    relatedProducts: list[Any] = []
    suggestedActions: list[Any] = []
    toolCalls: list[Any] = []
