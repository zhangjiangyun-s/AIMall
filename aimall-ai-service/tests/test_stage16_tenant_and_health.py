import asyncio

import pytest
from pydantic import ValidationError

from app.api.health_api import health, startup_health
from app.schemas.chat_schema import ChatRequest, PendingActionRequest, SessionClearRequest


def test_public_liveness_is_minimal_and_stable():
    payload = asyncio.run(health())
    assert set(payload) == {"service", "status", "instance"}
    assert payload["status"] == "UP"


def test_startup_reports_explicit_single_tenant_mode():
    payload = asyncio.run(startup_health())
    assert payload["status"] == "UP"
    assert payload["configuration"]["tenantMode"] == "SINGLE_TENANT"


@pytest.mark.parametrize(
    "factory",
    [
        lambda: ChatRequest(message="hello", sessionId="session-1", tenantId="tenant-b"),
        lambda: SessionClearRequest(sessionId="session-1", tenantId="tenant-b"),
        lambda: PendingActionRequest(sessionId="session-1", tenantId="tenant-b"),
    ],
)
def test_single_tenant_mode_rejects_non_default_tenant(factory):
    with pytest.raises(ValidationError):
        factory()
