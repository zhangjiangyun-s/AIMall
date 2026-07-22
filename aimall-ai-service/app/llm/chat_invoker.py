from __future__ import annotations

from typing import Any

from app.llm.agnes_client import AgnesClient, agnes_client
from app.llm.model_router import ModelPurpose


async def invoke_chat(
    message: str,
    system_prompt: str,
    context: dict[str, Any] | None = None,
    *,
    purpose: ModelPurpose | str = ModelPurpose.GENERATION,
    client: AgnesClient = agnes_client,
) -> str:
    """Invoke the routed client while retaining compatibility with legacy test doubles."""
    try:
        return await client.chat(message, system_prompt, context, purpose=purpose)
    except TypeError as exc:
        if "unexpected keyword argument 'purpose'" not in str(exc):
            raise
        return await client.chat(message, system_prompt, context)
