from fastapi import FastAPI

from app.api.health_api import router as health_router
from app.api.chat_api import router as chat_router
from app.api.knowledge_api import router as knowledge_router

app = FastAPI(title="aimall-ai-service", version="0.1.0")

app.include_router(health_router)
app.include_router(chat_router)
app.include_router(knowledge_router)
