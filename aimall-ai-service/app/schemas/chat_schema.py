from typing import Any, Optional

from pydantic import BaseModel, Field, field_validator

from app.config.settings import settings


def validate_tenant_id(value: str) -> str:
    tenant_id = value.strip()
    if settings.TENANT_MODE == "SINGLE_TENANT" and tenant_id != settings.DEFAULT_TENANT_ID:
        raise ValueError("non-default tenantId is forbidden in SINGLE_TENANT mode")
    return tenant_id


class PageContext(BaseModel):
    pageType: Optional[str] = None
    productId: Optional[int] = None
    orderId: Optional[int] = None
    keyword: Optional[str] = None
    categoryId: Optional[int] = None
    cartItemCount: Optional[int] = None


class AuthContext(BaseModel):
    token: Optional[str] = None
    channel: Optional[str] = None


class ChatRequest(BaseModel):
    userId: Optional[int] = None
    tenantId: str = Field(default="default", min_length=1, max_length=64)
    message: str
    sessionId: str = Field(min_length=1, max_length=160)
    traceId: Optional[str] = None
    pageContext: Optional[PageContext] = None
    authContext: Optional[AuthContext] = None

    _tenant_policy = field_validator("tenantId")(validate_tenant_id)


class ChatResponse(BaseModel):
    answer: str
    intent: str
    traceId: str
    relatedProducts: list[Any] = Field(default_factory=list)
    suggestedActions: list[Any] = Field(default_factory=list)
    toolCalls: list[Any] = Field(default_factory=list)


class AiFeedbackRequest(BaseModel):
    traceId: str
    feedbackType: str
    userId: Optional[int] = None
    sessionId: str = Field(min_length=1, max_length=160)
    userComment: Optional[str] = None
    correctSnippet: Optional[str] = None
    authContext: Optional[AuthContext] = None


class SessionClearRequest(BaseModel):
    sessionId: str = Field(min_length=1, max_length=160)
    userId: Optional[int] = None
    tenantId: str = Field(default="default", min_length=1, max_length=64)
    authContext: Optional[AuthContext] = None

    _tenant_policy = field_validator("tenantId")(validate_tenant_id)


class PendingActionRequest(BaseModel):
    sessionId: str = Field(min_length=1, max_length=160)
    userId: Optional[int] = None
    tenantId: str = Field(default="default", min_length=1, max_length=64)
    actionVersion: int = Field(default=1, ge=1)
    authContext: Optional[AuthContext] = None

    _tenant_policy = field_validator("tenantId")(validate_tenant_id)
