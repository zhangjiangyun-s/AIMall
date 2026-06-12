from fastapi import APIRouter

router = APIRouter(prefix="/ai", tags=["Knowledge"])


@router.post("/knowledge/rebuild")
async def rebuild_knowledge():
    return {
        "success": True,
        "message": "知识库重建 mock 完成",
        "docCount": 0,
    }
