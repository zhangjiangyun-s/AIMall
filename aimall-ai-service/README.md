# aimall-ai-service

AI service backend built with FastAPI. Runtime behavior is selected explicitly through
`MOCK`, `RULE_BASED`, `LLM`, `SANDBOX`, or `PRODUCTION` capability modes.

## Installation

```bash
pip install -r requirements.txt
```

## Startup

```bash
uvicorn main:app --reload --port 8000
```

## Port

8000

## Runtime Mode

Configure `AI_RUNTIME_MODE`, `AI_RUNTIME_MODE_PREVIOUS`,
`AI_RUNTIME_MODE_ROLLOUT_PERCENT`, `AI_RUNTIME_MODE_CHANGE_ID`, and
`AIMALL_INSTANCE_KEY`. The resolved mode is stable per instance, is recorded with a
capability hash in `RUNTIME_MODE_AUDIT_LOG`, and is exposed by authenticated startup
and readiness health endpoints.

Production requires Redis-backed state. Read-only chat can degrade to stateless mode
when Redis is unavailable; pending Actions and write tools fail closed with
`AI_STATE_UNAVAILABLE`.

## Implemented APIs

| Method | Path | Description |
| ------ | ---- | ----------- |
| GET | /health | Health check |
| GET | /health/integration | Authenticated dependency and capability health |
| POST | /ai/chat | Guarded agent chat and SSE events |
| POST | /ai/actions/{id}/confirm | Fenced pending Action confirmation |
| POST | /ai/knowledge/process | Versioned knowledge processing task |
| POST | /ai/vector/sync | Fenced vector synchronization |

## Project Structure

```
main.py
app/
+-- api/
|   +-- chat_api.py
|   +-- health_api.py
|   +-- knowledge_api.py
+-- config/
|   +-- settings.py
+-- router/
|   +-- intent_router.py
+-- schemas/
|   +-- chat_schema.py
+-- tools/
|   +-- java_client.py
```

## Stage 19 Release Gate

The fixed offline dataset is `data/evaluation/evalset-v1-manifest.json`. A release
requires three independent runs against one `publicationVersion` and
`retrievalEpoch`; the worst metric from the three runs is evaluated by
`app.evaluation.stage19_rag_quality_gate`.
