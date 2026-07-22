import os
import hashlib
from urllib.parse import quote
from pathlib import Path


def load_env_file() -> None:
    root = Path(__file__).resolve().parents[3]
    for env_file in (root / ".env", root / "aimall-ai-service" / ".env"):
        if not env_file.exists():
            continue
        for line in env_file.read_text(encoding="utf-8").splitlines():
            text = line.strip()
            if not text or text.startswith("#") or "=" not in text:
                continue
            key, value = text.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


load_env_file()


def resolve_redis_url() -> str:
    configured = os.getenv("REDIS_URL", "").strip()
    if configured:
        return configured
    password = os.getenv("REDIS_PASSWORD", "").strip() or os.getenv("AIMALL_INTERNAL_API_SECRET", "").strip()
    auth = f":{quote(password, safe='')}@" if password else ""
    return f"redis://{auth}127.0.0.1:6379/0"


AI_RUNTIME_MODES = {"MOCK", "RULE_BASED", "LLM", "SANDBOX", "PRODUCTION"}
RAG_RETRIEVAL_MODES = {"DOC_ONLY", "HYBRID", "VECTOR"}
TENANT_MODES = {"SINGLE_TENANT", "MULTI_TENANT"}
LEGACY_RAG_MODE_ALIASES = {
    "HYBRID_SHADOW": "HYBRID",
    "CHUNK_WITH_DOC_FALLBACK": "HYBRID",
    "CHUNK_ONLY": "VECTOR",
}


def normalize_ai_runtime_mode(value: str | None) -> str:
    mode = (value or "LLM").strip().upper()
    if mode not in AI_RUNTIME_MODES:
        raise RuntimeError(f"Unsupported AI_RUNTIME_MODE: {mode}")
    return mode


def resolve_ai_runtime_mode(target: str, previous: str, rollout_percent: int, instance_key: str) -> str:
    if not 0 <= rollout_percent <= 100:
        raise RuntimeError("AI_RUNTIME_MODE_ROLLOUT_PERCENT must be between 0 and 100")
    if rollout_percent == 100 or target == previous:
        return target
    if rollout_percent == 0:
        return previous
    bucket = int(hashlib.sha256(instance_key.encode("utf-8")).hexdigest()[:8], 16) % 100
    return target if bucket < rollout_percent else previous


def normalize_tenant_mode(value: str | None) -> str:
    mode = (value or "SINGLE_TENANT").strip().upper()
    if mode not in TENANT_MODES:
        raise ValueError("AIMALL_TENANT_MODE must be SINGLE_TENANT or MULTI_TENANT")
    return mode


def normalize_rag_retrieval_mode(value: str | None) -> str:
    mode = (value or "DOC_ONLY").strip().upper()
    mode = LEGACY_RAG_MODE_ALIASES.get(mode, mode)
    return mode if mode in RAG_RETRIEVAL_MODES else "DOC_ONLY"


def env_bool(name: str, default: bool = False) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


class Settings:
    SERVICE_NAME: str = "aimall-ai-service"
    PORT: int = 8000
    HOST: str = "0.0.0.0"
    AIMALL_SERVER_BASE_URL: str = os.getenv("AIMALL_SERVER_BASE_URL", "http://localhost:8080").rstrip("/")
    AIMALL_ENVIRONMENT: str = os.getenv("AIMALL_ENVIRONMENT", "local").strip().lower()
    TENANT_MODE: str = normalize_tenant_mode(os.getenv("AIMALL_TENANT_MODE", "SINGLE_TENANT"))
    DEFAULT_TENANT_ID: str = os.getenv("AIMALL_DEFAULT_TENANT_ID", "default").strip()
    AIMALL_INTERNAL_API_SECRET: str = os.getenv("AIMALL_INTERNAL_API_SECRET", "")
    JAVA_TO_AI_CURRENT_KEY_ID: str = os.getenv("AIMALL_JAVA_TO_AI_KEY_ID", "legacy").strip()
    JAVA_TO_AI_CURRENT_SECRET: str = os.getenv(
        "AIMALL_JAVA_TO_AI_SECRET", os.getenv("AIMALL_INTERNAL_API_SECRET", "")
    ).strip()
    JAVA_TO_AI_PREVIOUS_KEY_ID: str = os.getenv("AIMALL_JAVA_TO_AI_PREVIOUS_KEY_ID", "").strip()
    JAVA_TO_AI_PREVIOUS_SECRET: str = os.getenv("AIMALL_JAVA_TO_AI_PREVIOUS_SECRET", "").strip()
    AI_TO_JAVA_KEY_ID: str = os.getenv("AIMALL_AI_TO_JAVA_KEY_ID", "legacy").strip()
    AI_TO_JAVA_SECRET: str = os.getenv(
        "AIMALL_AI_TO_JAVA_SECRET", os.getenv("AIMALL_INTERNAL_API_SECRET", "")
    ).strip()
    AI_RUNTIME_MODE_TARGET: str = normalize_ai_runtime_mode(os.getenv("AI_RUNTIME_MODE", "LLM"))
    AI_RUNTIME_MODE_PREVIOUS: str = normalize_ai_runtime_mode(
        os.getenv("AI_RUNTIME_MODE_PREVIOUS", os.getenv("AI_RUNTIME_MODE", "LLM"))
    )
    AI_RUNTIME_MODE_ROLLOUT_PERCENT: int = int(os.getenv("AI_RUNTIME_MODE_ROLLOUT_PERCENT", "100"))
    AI_RUNTIME_MODE_CHANGE_ID: str = os.getenv("AI_RUNTIME_MODE_CHANGE_ID", "initial").strip()
    AI_RUNTIME_INSTANCE_KEY: str = os.getenv("AIMALL_INSTANCE_KEY", os.getenv("HOSTNAME", "local-instance")).strip()
    AI_RUNTIME_MODE: str = resolve_ai_runtime_mode(
        AI_RUNTIME_MODE_TARGET,
        AI_RUNTIME_MODE_PREVIOUS,
        AI_RUNTIME_MODE_ROLLOUT_PERCENT,
        AI_RUNTIME_INSTANCE_KEY,
    )
    RAG_ENABLED: bool = env_bool("RAG_ENABLED", True)
    RUNTIME_MODE_AUDIT_LOG: str = os.getenv("RUNTIME_MODE_AUDIT_LOG", "logs/runtime-mode-audit.jsonl")
    AGNES_API_KEY: str = os.getenv("AGNES_API_KEY", "")
    AGNES_BASE_URL: str = os.getenv("AGNES_BASE_URL", "https://apihub.agnes-ai.com/v1").rstrip("/")
    AGNES_MODEL: str = os.getenv("AGNES_MODEL", "agnes-2.0-flash")
    AGNES_INPUT_COST_PER_MILLION_USD: float = float(os.getenv("AGNES_INPUT_COST_PER_MILLION_USD", "0"))
    AGNES_OUTPUT_COST_PER_MILLION_USD: float = float(os.getenv("AGNES_OUTPUT_COST_PER_MILLION_USD", "0"))
    # Every model defaults to the legacy value so enabling routing is behavior-compatible.
    MODEL_ROUTING_ENABLED: bool = env_bool("MODEL_ROUTING_ENABLED", True)
    AGNES_FAST_MODEL: str = os.getenv("AGNES_FAST_MODEL", os.getenv("AGNES_MODEL", "agnes-2.0-flash"))
    AGNES_PRIMARY_MODEL: str = os.getenv("AGNES_PRIMARY_MODEL", os.getenv("AGNES_MODEL", "agnes-2.0-flash"))
    AGNES_FALLBACK_MODEL: str = os.getenv("AGNES_FALLBACK_MODEL", "")
    AGNES_FAST_INPUT_COST_PER_MILLION_USD: float = float(
        os.getenv("AGNES_FAST_INPUT_COST_PER_MILLION_USD", os.getenv("AGNES_INPUT_COST_PER_MILLION_USD", "0"))
    )
    AGNES_FAST_OUTPUT_COST_PER_MILLION_USD: float = float(
        os.getenv("AGNES_FAST_OUTPUT_COST_PER_MILLION_USD", os.getenv("AGNES_OUTPUT_COST_PER_MILLION_USD", "0"))
    )
    AGNES_PRIMARY_INPUT_COST_PER_MILLION_USD: float = float(
        os.getenv("AGNES_PRIMARY_INPUT_COST_PER_MILLION_USD", os.getenv("AGNES_INPUT_COST_PER_MILLION_USD", "0"))
    )
    AGNES_PRIMARY_OUTPUT_COST_PER_MILLION_USD: float = float(
        os.getenv("AGNES_PRIMARY_OUTPUT_COST_PER_MILLION_USD", os.getenv("AGNES_OUTPUT_COST_PER_MILLION_USD", "0"))
    )
    AGNES_FALLBACK_INPUT_COST_PER_MILLION_USD: float = float(
        os.getenv("AGNES_FALLBACK_INPUT_COST_PER_MILLION_USD", os.getenv("AGNES_INPUT_COST_PER_MILLION_USD", "0"))
    )
    AGNES_FALLBACK_OUTPUT_COST_PER_MILLION_USD: float = float(
        os.getenv("AGNES_FALLBACK_OUTPUT_COST_PER_MILLION_USD", os.getenv("AGNES_OUTPUT_COST_PER_MILLION_USD", "0"))
    )
    MULTI_AGENT_ENABLED: bool = env_bool("MULTI_AGENT_ENABLED", False)
    MULTI_AGENT_MAX_DELEGATIONS: int = int(os.getenv("MULTI_AGENT_MAX_DELEGATIONS", "3"))
    OBS_ALERT_LLM_FAILURE_RATE: float = float(os.getenv("OBS_ALERT_LLM_FAILURE_RATE", "0.10"))
    OBS_ALERT_TOOL_FAILURE_RATE: float = float(os.getenv("OBS_ALERT_TOOL_FAILURE_RATE", "0.10"))
    OBS_ALERT_DEGRADED_RATE: float = float(os.getenv("OBS_ALERT_DEGRADED_RATE", "0.10"))
    OBS_ALERT_RAG_NO_MATCH_RATE: float = float(os.getenv("OBS_ALERT_RAG_NO_MATCH_RATE", "0.40"))
    OBS_ALERT_LLM_P95_MS: int = int(os.getenv("OBS_ALERT_LLM_P95_MS", "30000"))
    OBS_ALERT_RAG_P95_MS: int = int(os.getenv("OBS_ALERT_RAG_P95_MS", "10000"))
    OBSERVABILITY_TOKEN: str = os.getenv("AIMALL_OBSERVABILITY_TOKEN", "").strip()
    LOG_RETENTION_DAYS: int = int(os.getenv("LOG_RETENTION_DAYS", "30"))
    AUDIT_RETENTION_DAYS: int = int(os.getenv("AUDIT_RETENTION_DAYS", "180"))
    LLM_TIMEOUT: int = int(os.getenv("LLM_TIMEOUT", "180"))
    TOOL_TIMEOUT: int = int(os.getenv("TOOL_TIMEOUT", "8"))
    RAG_TOOL_TIMEOUT: int = int(os.getenv("RAG_TOOL_TIMEOUT", "75"))
    VECTOR_DELETION_SCAN_SECONDS: int = int(os.getenv("VECTOR_DELETION_SCAN_SECONDS", "30"))
    TRACE_LOG_DIR: str = os.getenv("TRACE_LOG_DIR", "logs/agent-traces")
    FEEDBACK_LOG_DIR: str = os.getenv("FEEDBACK_LOG_DIR", "logs/rag-feedback")
    METRICS_LOG_DIR: str = os.getenv("METRICS_LOG_DIR", "logs/agent-metrics")
    SESSION_MEMORY_TTL_SECONDS: int = int(os.getenv("SESSION_MEMORY_TTL_SECONDS", "86400"))
    SESSION_MEMORY_MAX_TURNS: int = int(os.getenv("SESSION_MEMORY_MAX_TURNS", "6"))
    SESSION_MEMORY_MAX_SESSIONS: int = int(os.getenv("SESSION_MEMORY_MAX_SESSIONS", "10000"))
    SESSION_MEMORY_MAX_USER_CHARS: int = int(os.getenv("SESSION_MEMORY_MAX_USER_CHARS", "1000"))
    SESSION_MEMORY_MAX_ASSISTANT_CHARS: int = int(os.getenv("SESSION_MEMORY_MAX_ASSISTANT_CHARS", "2000"))
    SESSION_MEMORY_MAX_ENTITIES_PER_TURN: int = int(os.getenv("SESSION_MEMORY_MAX_ENTITIES_PER_TURN", "8"))
    SESSION_MEMORY_MAX_CONTEXT_ENTITIES: int = int(os.getenv("SESSION_MEMORY_MAX_CONTEXT_ENTITIES", "12"))
    SESSION_MEMORY_MAX_SUMMARY_ENTITIES: int = int(os.getenv("SESSION_MEMORY_MAX_SUMMARY_ENTITIES", "24"))
    SESSION_MEMORY_CONTEXT_TOKEN_BUDGET: int = int(os.getenv("SESSION_MEMORY_CONTEXT_TOKEN_BUDGET", "4000"))
    SESSION_MEMORY_SUMMARY_MAX_CHARS: int = int(os.getenv("SESSION_MEMORY_SUMMARY_MAX_CHARS", "1500"))
    SESSION_SUMMARY_TIMEOUT: int = int(os.getenv("SESSION_SUMMARY_TIMEOUT", "20"))
    PENDING_ACTION_TTL_SECONDS: int = int(os.getenv("PENDING_ACTION_TTL_SECONDS", "600"))
    PENDING_ACTION_EXECUTION_LEASE_SECONDS: int = int(os.getenv("PENDING_ACTION_EXECUTION_LEASE_SECONDS", "180"))
    PENDING_ACTION_RESULT_TTL_SECONDS: int = int(os.getenv("PENDING_ACTION_RESULT_TTL_SECONDS", "86400"))
    PENDING_ACTION_MAX_COUNT: int = int(os.getenv("PENDING_ACTION_MAX_COUNT", "10000"))
    SSE_MAX_GLOBAL_CONNECTIONS: int = int(os.getenv("SSE_MAX_GLOBAL_CONNECTIONS", "200"))
    SSE_MAX_CONNECTIONS_PER_CLIENT: int = int(os.getenv("SSE_MAX_CONNECTIONS_PER_CLIENT", "4"))
    SSE_MAX_DURATION_SECONDS: int = int(os.getenv("SSE_MAX_DURATION_SECONDS", "180"))
    ACTION_AUDIT_LOG_DIR: str = os.getenv("ACTION_AUDIT_LOG_DIR", "logs/action-audit")
    STATE_BACKEND: str = os.getenv("STATE_BACKEND", "memory").strip().lower()
    REDIS_URL: str = resolve_redis_url()
    REDIS_CONNECT_TIMEOUT_SECONDS: float = float(os.getenv("REDIS_CONNECT_TIMEOUT_SECONDS", "2"))
    REDIS_SOCKET_TIMEOUT_SECONDS: float = float(os.getenv("REDIS_SOCKET_TIMEOUT_SECONDS", "2"))
    REDIS_MAX_CONNECTIONS: int = int(os.getenv("REDIS_MAX_CONNECTIONS", "100"))
    STATE_KEY_PREFIX: str = os.getenv("STATE_KEY_PREFIX", "aimall:state:v1").strip().rstrip(":")
    GUARDRAIL_MAX_INPUT_CHARS: int = int(os.getenv("GUARDRAIL_MAX_INPUT_CHARS", "4000"))
    GUARDRAIL_MAX_TOOL_ARGUMENT_CHARS: int = int(os.getenv("GUARDRAIL_MAX_TOOL_ARGUMENT_CHARS", "8000"))
    GUARDRAIL_MAX_EVIDENCE_CHARS: int = int(os.getenv("GUARDRAIL_MAX_EVIDENCE_CHARS", "12000"))
    REFLECTION_JUDGE_ENABLED: bool = env_bool("REFLECTION_JUDGE_ENABLED", False)
    REFLECTION_JUDGE_TIMEOUT: float = float(os.getenv("REFLECTION_JUDGE_TIMEOUT", "45"))
    REFLECTION_JUDGE_MIN_CONFIDENCE: float = float(os.getenv("REFLECTION_JUDGE_MIN_CONFIDENCE", "0.85"))
    REFLECTION_JUDGE_MAX_EVIDENCE_CHARS: int = int(os.getenv("REFLECTION_JUDGE_MAX_EVIDENCE_CHARS", "8000"))
    REFLECTION_JUDGE_INTENTS: tuple[str, ...] = tuple(
        item.strip().upper()
        for item in os.getenv(
            "REFLECTION_JUDGE_INTENTS",
            "POLICY_QA,PRODUCT_QA,ORDER_QA,RETURN_QA,RECOMMENDATION",
        ).split(",")
        if item.strip()
    )
    RAG_RETRIEVAL_MODE: str = normalize_rag_retrieval_mode(os.getenv("RAG_RETRIEVAL_MODE", "DOC_ONLY"))
    MILVUS_URI: str = os.getenv("MILVUS_URI", "http://127.0.0.1:19530")
    MILVUS_CONNECT_TIMEOUT_SECONDS: float = float(os.getenv("MILVUS_CONNECT_TIMEOUT_SECONDS", "3"))
    MILVUS_COLLECTION: str = os.getenv("MILVUS_COLLECTION", "aimall_knowledge_chunks_doubao")
    EMBEDDING_PROVIDER: str = os.getenv("EMBEDDING_PROVIDER", "ark")
    EMBEDDING_MODEL: str = os.getenv("EMBEDDING_MODEL", "ep-20260411161754-h256l")
    EMBEDDING_MODEL_VERSION: str = os.getenv("EMBEDDING_MODEL_VERSION", "doubao-embedding-vision-251215")
    EMBEDDING_DIM: int = int(os.getenv("EMBEDDING_DIM", "2048"))
    ARK_API_KEY: str = os.getenv("ARK_API_KEY", "")
    ARK_EMBEDDING_URL: str = os.getenv(
        "ARK_EMBEDDING_URL",
        "https://ark.cn-beijing.volces.com/api/v3/embeddings/multimodal",
    )


settings = Settings()


def validate_model_routing_settings() -> None:
    if not settings.MODEL_ROUTING_ENABLED:
        return
    if not settings.AGNES_FAST_MODEL.strip() or not settings.AGNES_PRIMARY_MODEL.strip():
        raise RuntimeError("AGNES_FAST_MODEL and AGNES_PRIMARY_MODEL are required when model routing is enabled")
    price_pairs = (
        ("FAST", settings.AGNES_FAST_INPUT_COST_PER_MILLION_USD, settings.AGNES_FAST_OUTPUT_COST_PER_MILLION_USD),
        ("PRIMARY", settings.AGNES_PRIMARY_INPUT_COST_PER_MILLION_USD, settings.AGNES_PRIMARY_OUTPUT_COST_PER_MILLION_USD),
        ("FALLBACK", settings.AGNES_FALLBACK_INPUT_COST_PER_MILLION_USD, settings.AGNES_FALLBACK_OUTPUT_COST_PER_MILLION_USD),
    )
    for role, input_price, output_price in price_pairs:
        if input_price < 0 or output_price < 0 or ((input_price == 0) != (output_price == 0)):
            raise RuntimeError(f"AGNES_{role} input/output prices must both be zero or both be positive")


def validate_multi_agent_settings() -> None:
    if not 1 <= settings.MULTI_AGENT_MAX_DELEGATIONS <= 3:
        raise RuntimeError("MULTI_AGENT_MAX_DELEGATIONS must be between 1 and 3")


def validate_runtime_settings() -> None:
    if settings.DEFAULT_TENANT_ID != "default":
        raise RuntimeError("AIMALL_DEFAULT_TENANT_ID must be default in SINGLE_TENANT mode")
    if settings.TENANT_MODE != "SINGLE_TENANT":
        raise RuntimeError("MULTI_TENANT is not production-ready; use SINGLE_TENANT")
    if settings.AIMALL_ENVIRONMENT not in {"prod", "production"}:
        return
    if not settings.AI_RUNTIME_MODE_CHANGE_ID:
        raise RuntimeError("AI_RUNTIME_MODE_CHANGE_ID is required in production")
    if len(settings.JAVA_TO_AI_CURRENT_SECRET) < 32 or len(settings.AI_TO_JAVA_SECRET) < 32:
        raise RuntimeError("directional internal API secrets must each be at least 32 characters in production")
    if settings.JAVA_TO_AI_CURRENT_SECRET == settings.AI_TO_JAVA_SECRET:
        raise RuntimeError("Java-to-AI and AI-to-Java secrets must be different in production")
    if not settings.JAVA_TO_AI_CURRENT_KEY_ID or not settings.AI_TO_JAVA_KEY_ID:
        raise RuntimeError("directional internal API key IDs are required in production")
    if settings.STATE_BACKEND != "redis":
        raise RuntimeError("STATE_BACKEND=redis is required in production")
    if settings.AI_RUNTIME_MODE != "PRODUCTION":
        raise RuntimeError("AI_RUNTIME_MODE=PRODUCTION is required in production")
    if not settings.RAG_ENABLED:
        raise RuntimeError("RAG_ENABLED=true is required in production")
    if not settings.AGNES_API_KEY.strip() or not settings.AGNES_BASE_URL or not settings.AGNES_MODEL:
        raise RuntimeError("AGNES_API_KEY, AGNES_BASE_URL and AGNES_MODEL are required in production")
    if settings.MODEL_ROUTING_ENABLED:
        validate_model_routing_settings()
    validate_multi_agent_settings()
    if not settings.AIMALL_SERVER_BASE_URL or "localhost" in settings.AIMALL_SERVER_BASE_URL:
        raise RuntimeError("AIMALL_SERVER_BASE_URL must use the service network in production")
    if not settings.ARK_API_KEY.strip():
        raise RuntimeError("ARK_API_KEY is required in production")
    if len(settings.OBSERVABILITY_TOKEN) < 32:
        raise RuntimeError("AIMALL_OBSERVABILITY_TOKEN must be at least 32 characters in production")


validate_runtime_settings()
