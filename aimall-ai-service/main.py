import asyncio
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, Request
from fastapi.responses import JSONResponse

from app.api.health_api import public_router as public_health_router, router as health_router
from app.api.chat_api import router as chat_router
from app.api.knowledge_api import router as knowledge_router
from app.api.tool_api import router as tool_router
from app.api.vector_api import router as vector_router
from app.api.action_api import router as action_router
from app.api.metrics_api import router as metrics_router
from app.llm.agnes_client import agnes_client
from app.memory import session_memory_store
from app.actions import pending_action_store
from app.tools.java_client import java_client
from app.security import require_internal_service
from app.security.sse_limiter import SseLimitMiddleware
from app.config.settings import settings
from app.rag.vector_sync import vector_deletion_worker
from app.state.redis_backend import AiStateUnavailableError
from app.observability.logging_config import configure_logging
from app.observability.middleware import TraceContextMiddleware
from app.runtime import runtime_capabilities
import logging


configure_logging()
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    capability_record = runtime_capabilities.audit_startup()
    logger.info(
        "AI runtime mode resolved mode=%s capabilityHash=%s changeId=%s",
        capability_record["mode"],
        capability_record["capabilityHash"],
        capability_record["featureFlag"]["changeId"],
    )
    deletion_task = asyncio.create_task(vector_deletion_worker())
    try:
        yield
    finally:
        deletion_task.cancel()
        await asyncio.gather(deletion_task, return_exceptions=True)
        await agnes_client.close()
        await java_client.close()
        await session_memory_store.close()
        await pending_action_store.close()


app = FastAPI(title="aimall-ai-service", version="0.1.0", lifespan=lifespan)
app.add_middleware(
    SseLimitMiddleware,
    max_global=settings.SSE_MAX_GLOBAL_CONNECTIONS,
    max_per_client=settings.SSE_MAX_CONNECTIONS_PER_CLIENT,
    max_duration_seconds=settings.SSE_MAX_DURATION_SECONDS,
)
app.add_middleware(TraceContextMiddleware)


@app.exception_handler(AiStateUnavailableError)
async def state_unavailable_handler(_request: Request, _exc: AiStateUnavailableError):
    return JSONResponse(
        status_code=503,
        content={
            "code": 1,
            "message": "AI state is temporarily unavailable",
            "data": {"success": False, "errorCode": "AI_STATE_UNAVAILABLE"},
        },
    )

app.include_router(public_health_router)
app.include_router(metrics_router)
internal_dependencies = [Depends(require_internal_service)]
app.include_router(health_router, dependencies=internal_dependencies)
app.include_router(chat_router, dependencies=internal_dependencies)
app.include_router(knowledge_router, dependencies=internal_dependencies)
app.include_router(tool_router, dependencies=internal_dependencies)
app.include_router(vector_router, dependencies=internal_dependencies)
app.include_router(action_router, dependencies=internal_dependencies)
