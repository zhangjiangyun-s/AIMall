import asyncio

from fastapi import APIRouter, Query
from pydantic import BaseModel

from app.config.settings import settings
from app.rag.embedding import embedding_provider
from app.rag.milvus_store import milvus_store
from app.rag.vector_sync import (
    ExecutionFenceLost,
    _compensate_lost_execution,
    _require_current_execution,
    process_pending_vector_deletions,
)
from app.tools.java_client import java_client

router = APIRouter(prefix="/ai/vector", tags=["Vector"])


class DocumentVectorStatusRequest(BaseModel):
    status: str
    isDeleted: bool = False
    publicationVersion: str | None = None
    retrievalEpoch: int | None = None


@router.get("/health")
async def vector_health():
    return {
        "code": 0,
        "message": "success",
        "data": {
            **milvus_store.health(),
            "embeddingModel": settings.EMBEDDING_MODEL,
            "embeddingModelVersion": settings.EMBEDDING_MODEL_VERSION,
            "embeddingProvider": settings.EMBEDDING_PROVIDER,
            "embeddingDim": settings.EMBEDDING_DIM,
        },
    }


@router.post("/sync")
async def sync_vectors(
    sync_status: str = Query("PENDING", alias="syncStatus"),
    limit: int = Query(100, ge=1, le=500),
    rebuild_chunks: bool = Query(False, alias="rebuildChunks"),
):
    deletion_result = await process_pending_vector_deletions(limit=limit)
    chunks = await java_client.list_vector_sync_chunks(sync_status=sync_status, limit=limit)
    rebuild_result = None
    if not chunks and rebuild_chunks:
        rebuild_result = await java_client.rebuild_knowledge_chunks()
        chunks = await java_client.list_vector_sync_chunks(sync_status=sync_status, limit=limit)
    result = {
        "requestedStatus": sync_status,
        "deletionResult": deletion_result,
        "rebuildResult": rebuild_result,
        "total": len(chunks),
        "synced": 0,
        "failed": 0,
        "failures": [],
    }
    if not milvus_store.available:
        result["failed"] = len(chunks)
        result["failures"].append({"reason": "pymilvus is not installed"})
        return {"code": 0, "message": "success", "data": result}

    cleaned_versions: set[tuple[int, int, str]] = set()
    for chunk in chunks:
        chunk_id = int(chunk["id"])
        execution_task_id = str(chunk.get("executionTaskId") or "")
        execution_token = str(chunk.get("executionToken") or "")
        if not execution_task_id or not execution_token:
            result["failed"] += 1
            result["failures"].append(
                {"chunkId": chunk_id, "message": "No RUNNING knowledge task execution owns this chunk"}
            )
            continue
        execution_context = java_client.bind_knowledge_execution(execution_task_id, execution_token)
        try:
            execution = await _require_current_execution(execution_task_id)
            current_token = str(execution["executionToken"])
            version_key = (
                int(execution.get("docId") or chunk.get("docId") or 0),
                int(execution.get("docVersionId") or chunk.get("docVersionId") or 0),
                current_token,
            )
            if version_key not in cleaned_versions:
                await asyncio.to_thread(
                    milvus_store.delete_doc_version_embeddings_except_execution,
                    *version_key,
                )
                cleaned_versions.add(version_key)
            vector = await embedding_provider.embed(chunk.get("indexContent") or chunk.get("maskedContent") or "")
            execution = await _require_current_execution(execution_task_id)
            current_token = str(execution["executionToken"])
            embedding_id = milvus_store.upsert_chunk(
                {**chunk, "executionToken": current_token, "attemptNo": int(execution.get("attemptNo") or 0)},
                vector,
            )
            try:
                await _require_current_execution(execution_task_id)
            except Exception as fence_error:
                await _compensate_lost_execution([embedding_id], current_token)
                raise ExecutionFenceLost("Knowledge task execution lost after Milvus write") from fence_error
            await java_client.update_chunk_embedding(
                chunk_id,
                embedding_id=embedding_id,
                embedding_model=settings.EMBEDDING_MODEL,
                embedding_model_version=settings.EMBEDDING_MODEL_VERSION,
                embedding_sync_status="SYNCED",
                vector_collection=settings.MILVUS_COLLECTION,
            )
            result["synced"] += 1
        except ExecutionFenceLost as exc:
            result["failed"] += 1
            result["failures"].append({"chunkId": chunk_id, "message": str(exc)[:300]})
        except Exception as exc:
            try:
                await _require_current_execution(execution_task_id)
                await java_client.update_chunk_embedding(
                    chunk_id,
                    embedding_id=chunk.get("embeddingId"),
                    embedding_model=settings.EMBEDDING_MODEL,
                    embedding_model_version=settings.EMBEDDING_MODEL_VERSION,
                    embedding_sync_status="FAILED",
                    vector_collection=settings.MILVUS_COLLECTION,
                )
            except ExecutionFenceLost:
                pass
            result["failed"] += 1
            result["failures"].append({"chunkId": chunk_id, "message": str(exc)[:300]})
        finally:
            java_client.reset_knowledge_execution(execution_context)

    return {"code": 0, "message": "success", "data": result}


@router.post("/deletions/process")
async def process_vector_deletions(limit: int = Query(100, ge=1, le=500)):
    return {
        "code": 0,
        "message": "success",
        "data": await process_pending_vector_deletions(limit=limit),
    }


@router.post("/consistency-check")
async def consistency_check(limit: int = Query(500, ge=1, le=1000)):
    chunks = await java_client.list_vector_sync_chunks(sync_status=None, limit=limit)
    result = {
        "checked": 0,
        "missing": 0,
        "modelMismatch": 0,
        "rescheduled": 0,
        "items": [],
    }
    if not milvus_store.available:
        result["items"].append({"reason": "pymilvus is not installed"})
        return {"code": 0, "message": "success", "data": result}

    for chunk in chunks:
        result["checked"] += 1
        execution_task_id = str(chunk.get("executionTaskId") or "")
        execution_token = str(chunk.get("executionToken") or "")
        if not execution_task_id or not execution_token:
            result["items"].append({"chunkId": chunk["id"], "rescheduleError": "No RUNNING knowledge task execution owns this chunk"})
            continue
        execution_context = java_client.bind_knowledge_execution(execution_task_id, execution_token)
        try:
            execution = await _require_current_execution(execution_task_id)
            expected_embedding_id = milvus_store.embedding_id(
                {**chunk, "executionToken": str(execution["executionToken"])}
            )
            is_missing = not chunk.get("embeddingId") or not milvus_store.has_embedding(str(chunk.get("embeddingId")))
            is_model_mismatch = chunk.get("embeddingModelVersion") not in (None, "", settings.EMBEDDING_MODEL_VERSION)
            if is_missing:
                result["missing"] += 1
            if is_model_mismatch:
                result["modelMismatch"] += 1
            if is_missing or is_model_mismatch:
                await java_client.update_chunk_embedding(
                    int(chunk["id"]),
                    embedding_id=expected_embedding_id if not is_missing else chunk.get("embeddingId"),
                    embedding_model=settings.EMBEDDING_MODEL,
                    embedding_model_version=settings.EMBEDDING_MODEL_VERSION,
                    embedding_sync_status="PENDING",
                    vector_collection=settings.MILVUS_COLLECTION,
                )
                result["rescheduled"] += 1
                result["items"].append(
                    {"chunkId": chunk["id"], "missing": is_missing, "modelMismatch": is_model_mismatch}
                )
        except Exception as exc:
            result["items"].append({"chunkId": chunk["id"], "rescheduleError": str(exc)[:300]})
        finally:
            java_client.reset_knowledge_execution(execution_context)

    return {"code": 0, "message": "success", "data": result}


@router.post("/docs/{doc_id}/status")
async def update_document_vector_status(doc_id: int, request: DocumentVectorStatusRequest):
    updated = milvus_store.update_doc_status(
        doc_id, request.status, request.isDeleted, request.publicationVersion, request.retrievalEpoch
    )
    return {
        "code": 0,
        "message": "success",
        "data": {"docId": doc_id, "updated": updated, "status": request.status, "isDeleted": request.isDeleted},
    }


@router.post("/docs/{doc_id}/versions/{version_id}/status")
async def update_document_version_vector_status(
    doc_id: int,
    version_id: int,
    request: DocumentVectorStatusRequest,
):
    updated = milvus_store.update_doc_version_status(
        doc_id,
        version_id,
        request.status,
        request.isDeleted,
        request.publicationVersion,
        request.retrievalEpoch,
    )
    return {
        "code": 0,
        "message": "success",
        "data": {
            "docId": doc_id,
            "versionId": version_id,
            "updated": updated,
            "status": request.status,
            "isDeleted": request.isDeleted,
        },
    }


@router.get("/docs/{doc_id}/versions/{version_id}/status")
async def get_document_version_vector_status(doc_id: int, version_id: int):
    result = milvus_store.get_doc_version_status(doc_id, version_id)
    return {
        "code": 0,
        "message": "success",
        "data": {"docId": doc_id, "versionId": version_id, **result},
    }
