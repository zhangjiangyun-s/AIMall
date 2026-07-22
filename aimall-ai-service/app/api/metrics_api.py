from __future__ import annotations

import hmac
import re

from fastapi import APIRouter, Depends, Header, HTTPException, status
from fastapi.responses import PlainTextResponse

from app.actions import pending_action_store
from app.config.settings import settings
from app.observability.metrics_registry import agent_metrics
from app.rag.milvus_store import milvus_store
from app.state import redis_state_backend


router = APIRouter(tags=["Observability"])


def require_observability_token(
    authorization: str | None = Header(default=None),
    x_observability_token: str | None = Header(default=None),
) -> None:
    if not settings.OBSERVABILITY_TOKEN and settings.AIMALL_ENVIRONMENT not in {"prod", "production"}:
        return
    supplied = x_observability_token or ""
    if not supplied and authorization and authorization.startswith("Bearer "):
        supplied = authorization[7:].strip()
    if not settings.OBSERVABILITY_TOKEN or not hmac.compare_digest(settings.OBSERVABILITY_TOKEN, supplied):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="observability unauthorized")


@router.get("/observability/prometheus", response_class=PlainTextResponse)
async def prometheus_metrics(_auth: None = Depends(require_observability_token)):
    snapshot = agent_metrics.snapshot()
    redis_metrics = await redis_state_backend.metrics()
    try:
        action_counts = await pending_action_store.status_counts()
    except Exception:
        action_counts = {}
    milvus = milvus_store.health()
    lines: list[str] = []
    for name, value in snapshot.get("counters", {}).items():
        _metric(lines, "aimall_ai_" + _safe(name) + "_total", value)
    for name, value in snapshot.get("rates", {}).items():
        _metric(lines, "aimall_ai_" + _safe(name), value)
    for name, values in snapshot.get("latencyMs", {}).items():
        for percentile in ("p50", "p95", "p99", "avg"):
            _metric(lines, f"aimall_ai_{_safe(name)}_latency_ms_{percentile}", values.get(percentile, 0))
    for name, value in snapshot.get("gauges", {}).items():
        _metric(lines, "aimall_ai_" + _safe(name), value)
    _metric(lines, "aimall_ai_llm_cost_usd_total", snapshot.get("cost", {}).get("estimatedCost", 0))
    for action_status in ("PENDING", "EXECUTING", "EXPIRED", "FAILED", "DEAD_LETTER"):
        _metric(lines, "aimall_ai_pending_actions", action_counts.get(action_status, 0), {"status": action_status})
    _metric(lines, "aimall_ai_redis_up", 1 if redis_metrics.get("status") == "UP" else 0)
    for source, target in {
        "connectedClients": "connected_clients",
        "usedMemoryBytes": "used_memory_bytes",
        "expiredKeys": "expired_keys_total",
        "keyspaceHits": "keyspace_hits_total",
        "keyspaceMisses": "keyspace_misses_total",
        "latencyMs": "latency_ms",
        "poolMaxConnections": "pool_max_connections",
        "poolInUseConnections": "pool_in_use_connections",
        "poolUtilization": "pool_utilization",
    }.items():
        _metric(lines, "aimall_ai_redis_" + target, redis_metrics.get(source, 0))
    _metric(lines, "aimall_ai_milvus_up", 1 if milvus.get("status") == "UP" else 0)
    _metric(lines, "aimall_ai_milvus_vectors", milvus.get("rowCount", 0))
    return PlainTextResponse("\n".join(lines) + "\n", media_type="text/plain; version=0.0.4")


def _safe(value: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_:]", "_", value).lower()


def _metric(lines: list[str], name: str, value, labels: dict[str, str] | None = None) -> None:
    label_text = ""
    if labels:
        label_text = "{" + ",".join(f'{key}="{str(item).replace(chr(34), "")}"' for key, item in labels.items()) + "}"
    try:
        numeric = float(value)
    except (TypeError, ValueError):
        numeric = 0.0
    lines.append(f"{name}{label_text} {numeric}")
