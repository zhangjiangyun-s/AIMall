from fastapi import APIRouter

from app.tools.registry import list_tool_definitions

router = APIRouter(prefix="/ai/tools", tags=["Tools"])


@router.get("")
async def tools():
    return {
        "tools": list_tool_definitions(),
    }
