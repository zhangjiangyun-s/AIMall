from fastapi import APIRouter

from app.config.settings import settings
from app.llm.agnes_client import agnes_client
from app.llm.model_router import ModelPurpose, model_router
from app.observability.metrics_registry import agent_metrics
from app.rag.milvus_store import milvus_store
from app.tools.registry import list_tool_definitions
from app.state import redis_state_backend
from app.runtime import runtime_capabilities
import shutil
import threading
import uuid
from datetime import datetime, timezone
from fastapi.responses import JSONResponse

public_router = APIRouter(tags=["Health"])
router = APIRouter(tags=["Health"])
INSTANCE_ID = str(uuid.uuid4())


@public_router.get("/health")
@public_router.get("/health/liveness")
async def health():
    return {
        "service": settings.SERVICE_NAME,
        "status": "UP",
        "instance": INSTANCE_ID,
    }


@router.get("/health/startup")
async def startup_health():
    runtime = runtime_capabilities.snapshot()
    return {
        "service": settings.SERVICE_NAME,
        "status": "UP",
        "configuration": {"status": "UP", "tenantMode": settings.TENANT_MODE},
        "initialization": {"status": "UP"},
        **runtime,
    }


@router.get("/health/readiness/ai")
async def ai_readiness():
    started = datetime.now(timezone.utc)
    vector_health = milvus_store.health()
    redis_health = await redis_state_backend.health()
    reasons = []
    if settings.AI_RUNTIME_MODE in {"LLM", "SANDBOX", "PRODUCTION"} and not agnes_client.enabled:
        reasons.append("LLM_NOT_CONFIGURED")
    if settings.RAG_RETRIEVAL_MODE in {"HYBRID", "VECTOR"} and vector_health.get("status") != "UP":
        reasons.append("VECTOR_STORE_UNAVAILABLE")
    if settings.STATE_BACKEND == "redis" and redis_health.get("status") != "UP":
        reasons.append("AI_STATE_UNAVAILABLE")
    ready = not reasons
    latency_ms = max(0, int((datetime.now(timezone.utc) - started).total_seconds() * 1000))
    payload = {
        "service": settings.SERVICE_NAME,
        "capability": "ai",
        "status": "UP" if ready else "DOWN",
        "latencyMs": latency_ms,
        "lastSuccessAt": datetime.now(timezone.utc).isoformat() if ready else "",
        "errorCode": "" if ready else reasons[0],
        "runtimeMode": settings.AI_RUNTIME_MODE,
        "ragMode": settings.RAG_RETRIEVAL_MODE,
        "stateStore": {"status": redis_health.get("status", "UNKNOWN")},
        "vectorStore": {"status": vector_health.get("status", "UNKNOWN")},
        **runtime_capabilities.snapshot(reasons),
    }
    return JSONResponse(status_code=200 if ready else 503, content=payload)


@router.get("/health/integration")
async def integration_health():
    generation_route = model_router.route(ModelPurpose.GENERATION)
    vector_health = milvus_store.health()
    redis_health = await redis_state_backend.health()
    disk = shutil.disk_usage(".")
    disk_ready = disk.free >= 512 * 1024 * 1024 and disk.free / max(1, disk.total) >= 0.05
    degradation_reasons = []
    if settings.AI_RUNTIME_MODE in {"LLM", "SANDBOX", "PRODUCTION"} and not agnes_client.enabled:
        degradation_reasons.append("LLM_NOT_CONFIGURED")
    if settings.RAG_RETRIEVAL_MODE in {"HYBRID", "VECTOR"} and vector_health.get("status") != "UP":
        degradation_reasons.append("VECTOR_STORE_UNAVAILABLE")
    if settings.STATE_BACKEND == "redis" and redis_health.get("status") != "UP":
        degradation_reasons.append("AI_STATE_UNAVAILABLE")
    if not disk_ready:
        degradation_reasons.append("DISK_CAPACITY_LOW")
    dependencies_ready = not degradation_reasons
    return {
        "service": "aimall-ai-service",
        "status": "UP" if dependencies_ready else "DEGRADED",
        "version": "2.0",
        "tenantMode": settings.TENANT_MODE,
        "runtimeMode": settings.AI_RUNTIME_MODE,
        "ragMode": settings.RAG_RETRIEVAL_MODE,
        "degradationReasons": degradation_reasons,
        **runtime_capabilities.snapshot(degradation_reasons),
        "llm": agnes_client.enabled,
        "model": generation_route.selected_model,
        "modelRouting": {
            "enabled": settings.MODEL_ROUTING_ENABLED,
            "generationModels": generation_route.attempted_models,
            "planningModels": model_router.route(ModelPurpose.PLANNING).attempted_models,
        },
        "vectorStore": vector_health,
        "stateStore": redis_health,
        "disk": {
            "status": "UP" if disk_ready else "DOWN",
            "freeBytes": disk.free,
            "totalBytes": disk.total,
        },
        "runtime": {
            "activeThreads": threading.active_count(),
        },
        "toolCount": len(list_tool_definitions()),
        "metricsSchemaVersion": agent_metrics.snapshot()["schemaVersion"],
        "tools": {
            "intentRouter": True,
            "policyQa": True,
            "productQa": True,
            "recommendation": True,
            "orderQa": True,
            "toolRegistry": True,
            "toolExecutor": True,
        },
    }


@router.get("/observability/summary")
async def observability_summary():
    """Return privacy-safe process metrics; raw traces remain in the audit log."""
    return agent_metrics.snapshot()


@router.get("/observability/health")
async def observability_health():
    return agent_metrics.health_summary(
        {
            "llmFailureRate": settings.OBS_ALERT_LLM_FAILURE_RATE,
            "toolFailureRate": settings.OBS_ALERT_TOOL_FAILURE_RATE,
            "degradedRate": settings.OBS_ALERT_DEGRADED_RATE,
            "ragNoMatchRate": settings.OBS_ALERT_RAG_NO_MATCH_RATE,
            "llmP95Ms": settings.OBS_ALERT_LLM_P95_MS,
            "ragP95Ms": settings.OBS_ALERT_RAG_P95_MS,
        }
    )


@router.get("/health/model")
async def model_health():
    generation_route = model_router.route(ModelPurpose.GENERATION)
    if not agnes_client.external_calls_allowed:
        return {
            "service": settings.SERVICE_NAME,
            "status": "DISABLED",
            "runtimeMode": settings.AI_RUNTIME_MODE,
            "model": None,
            "message": "External LLM calls are disabled by runtime mode",
        }
    if not agnes_client.enabled:
        return {
            "service": settings.SERVICE_NAME,
            "status": "DOWN",
            "runtimeMode": settings.AI_RUNTIME_MODE,
            "model": generation_route.selected_model,
            "message": "LLM is not configured",
        }
    try:
        result = await agnes_client.check()
        return {
            "service": settings.SERVICE_NAME,
            "status": "UP",
            "runtimeMode": settings.AI_RUNTIME_MODE,
            **result,
        }
    except Exception as exc:
        return {
            "service": settings.SERVICE_NAME,
            "status": "DOWN",
            "model": generation_route.selected_model,
            "message": str(exc),
        }
