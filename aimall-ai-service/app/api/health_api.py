from fastapi import APIRouter

from app.config.settings import settings

router = APIRouter(tags=["Health"])


@router.get("/health")
async def health():
    return {
        "service": settings.SERVICE_NAME,
        "status": "UP",
    }


@router.get("/health/integration")
async def integration_health():
    return {
        "service": "aimall-ai-service",
        "status": "UP",
        "version": "1D-mock",
        "llm": False,
        "vectorStore": False,
        "tools": {
            "intentRouter": True,
            "policyQa": True,
            "productQa": True,
            "recommendation": True,
            "orderQa": True,
        },
    }
