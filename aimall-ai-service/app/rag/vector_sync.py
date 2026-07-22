from __future__ import annotations

import asyncio
import logging
from typing import Any

from app.config.settings import settings
from app.rag.embedding import embedding_provider
from app.rag.milvus_store import milvus_store
from app.tools.java_client import java_client


BATCH_SIZE = 16
logger = logging.getLogger(__name__)


class ExecutionFenceLost(RuntimeError):
    pass


async def _require_current_execution(task_id: str) -> dict[str, Any]:
    task = await java_client.get_knowledge_task(task_id)
    if str(task.get("status") or "") != "RUNNING":
        raise ExecutionFenceLost("Knowledge task execution is no longer running")
    if not str(task.get("executionToken") or ""):
        raise ExecutionFenceLost("Knowledge task response omitted execution token")
    return task


async def _compensate_lost_execution(embedding_ids: list[str], execution_token: str) -> None:
    if not embedding_ids or not execution_token:
        return
    try:
        await asyncio.to_thread(milvus_store.delete_embeddings_for_execution, embedding_ids, execution_token)
    except Exception:
        logger.exception("stale_execution_vector_compensation_failed")


async def process_pending_vector_deletions(limit: int = 100) -> dict[str, Any]:
    chunks = await java_client.claim_vector_deletion_chunks(limit=limit)
    result: dict[str, Any] = {"total": len(chunks), "deleted": 0, "failed": 0, "failures": []}
    if chunks and not milvus_store.available:
        raise RuntimeError("pymilvus is not installed")
    for chunk in chunks:
        chunk_id = int(chunk["id"])
        embedding_id = str(chunk.get("embeddingId") or "")
        claim_token = str(chunk.get("vectorDeleteClaimToken") or "")
        try:
            if not claim_token:
                raise RuntimeError("Vector deletion candidate has no claim token")
            deleted = await asyncio.to_thread(milvus_store.delete_embedding, embedding_id)
            if not deleted:
                raise RuntimeError("Milvus still contains the embedding after delete")
            await java_client.complete_vector_deletion(chunk_id, claim_token)
            result["deleted"] += 1
        except Exception as exc:
            error_message = str(exc)[:1000]
            result["failed"] += 1
            result["failures"].append({"chunkId": chunk_id, "message": error_message[:300]})
            logger.error(
                "vector_delete_failed chunk_id=%s retry_count=%s error=%s",
                chunk_id,
                chunk.get("vectorDeleteRetryCount", 0),
                error_message,
            )
            if claim_token:
                try:
                    failure_state = await java_client.fail_vector_deletion(
                        chunk_id, claim_token, error_message
                    )
                    if failure_state.get("embeddingSyncStatus") == "DELETE_DEAD":
                        logger.critical(
                            "vector_delete_dead_letter chunk_id=%s retries=%s error=%s",
                            chunk_id,
                            failure_state.get("vectorDeleteRetryCount"),
                            error_message,
                        )
                except Exception as callback_error:
                    logger.exception(
                        "vector_delete_failure_callback_failed chunk_id=%s error=%s",
                        chunk_id,
                        callback_error,
                    )
    return result


async def vector_deletion_worker() -> None:
    interval = max(5, settings.VECTOR_DELETION_SCAN_SECONDS)
    while True:
        try:
            await process_pending_vector_deletions(limit=100)
        except asyncio.CancelledError:
            raise
        except Exception:
            logger.exception("vector_deletion_worker_scan_failed")
        await asyncio.sleep(interval)


async def sync_task_vectors(task_id: str) -> dict[str, Any]:
    chunks = await java_client.list_knowledge_task_chunks(task_id)
    execution = await _require_current_execution(task_id)
    execution_token = str(execution["executionToken"])
    candidates = [
        chunk
        for chunk in chunks
        if chunk.get("status") != "REVIEW_REQUIRED"
        and (
            chunk.get("embeddingSyncStatus") != "SYNCED"
            or str(chunk.get("embeddingId") or "")
            != milvus_store.embedding_id({**chunk, "executionToken": execution_token})
        )
    ]
    result: dict[str, Any] = {
        "total": len(candidates),
        "synced": 0,
        "failed": 0,
        "cacheHits": 0,
        "embeddingCalls": 0,
        "failures": [],
    }
    if not milvus_store.available:
        raise RuntimeError("pymilvus is not installed")
    await asyncio.to_thread(
        milvus_store.delete_doc_version_embeddings_except_execution,
        int(execution.get("docId") or 0),
        int(execution.get("docVersionId") or 0),
        execution_token,
    )
    if not candidates:
        result["blocked"] = len([chunk for chunk in chunks if chunk.get("status") == "REVIEW_REQUIRED"])
        return result

    await java_client.record_knowledge_task_event(
        task_id,
        event_type="embedding_started",
        title="开始生成向量",
        detail=f"共 {len(candidates)} 个 chunk，每批 {BATCH_SIZE} 个",
        progress_current=0,
        progress_total=len(candidates),
    )

    processed = 0
    for offset in range(0, len(candidates), BATCH_SIZE):
        batch = candidates[offset : offset + BATCH_SIZE]
        vectors: dict[int, list[float]] = {}
        missing_chunks: list[dict[str, Any]] = []

        for chunk in batch:
            cache = await java_client.get_embedding_cache(
                str(chunk.get("contentHash") or ""),
                settings.EMBEDDING_MODEL,
                int(chunk.get("retrievalEpoch") or 0),
            )
            cached_id = str(cache.get("embeddingId") or "") if cache.get("hit") else ""
            cached_vector = milvus_store.get_vector(cached_id) if cached_id else None
            if cached_vector is not None and len(cached_vector) == settings.EMBEDDING_DIM:
                vectors[int(chunk["id"])] = cached_vector
                result["cacheHits"] += 1
            else:
                missing_chunks.append(chunk)

        if missing_chunks:
            texts = [str(chunk.get("indexContent") or chunk.get("maskedContent") or "") for chunk in missing_chunks]
            embedded = await embedding_provider.embed_batch(texts, concurrency=4, max_retries=3)
            result["embeddingCalls"] += len(missing_chunks)
            for chunk, vector_or_error in zip(missing_chunks, embedded):
                chunk_id = int(chunk["id"])
                if isinstance(vector_or_error, Exception):
                    await mark_failed(chunk, vector_or_error)
                    result["failed"] += 1
                    result["failures"].append({"chunkId": chunk_id, "message": str(vector_or_error)[:300]})
                    continue
                vectors[chunk_id] = vector_or_error

        ready_items = [
            ({**chunk, "status": "READY_TO_PUBLISH"}, vectors[int(chunk["id"])])
            for chunk in batch
            if int(chunk["id"]) in vectors
        ]
        if ready_items:
            await java_client.record_knowledge_task_event(
                task_id,
                event_type="vector_sync_started",
                title="写入 Milvus",
                detail=f"批次 {offset // BATCH_SIZE + 1}，写入 {len(ready_items)} 条向量",
                progress_current=processed,
                progress_total=len(candidates),
            )
            try:
                execution = await _require_current_execution(task_id)
                execution_token = str(execution.get("executionToken") or "")
                attempt_no = int(execution.get("attemptNo") or 0)
                ready_items = [
                    ({**chunk, "executionToken": execution_token, "attemptNo": attempt_no}, vector)
                    for chunk, vector in ready_items
                ]
                embedding_ids = milvus_store.upsert_chunks(ready_items)
                try:
                    await _require_current_execution(task_id)
                except Exception as fence_error:
                    await _compensate_lost_execution(embedding_ids, execution_token)
                    raise ExecutionFenceLost("Knowledge task execution lost after Milvus write") from fence_error
                for (chunk, vector), embedding_id in zip(ready_items, embedding_ids):
                    await mark_synced(chunk, embedding_id, len(vector))
                    result["synced"] += 1
            except ExecutionFenceLost:
                raise
            except Exception:
                for chunk, vector in ready_items:
                    try:
                        execution = await _require_current_execution(task_id)
                        execution_token = str(execution.get("executionToken") or "")
                        item = {**chunk, "executionToken": execution_token, "attemptNo": int(execution.get("attemptNo") or 0)}
                        embedding_id = milvus_store.upsert_chunk(item, vector)
                        try:
                            await _require_current_execution(task_id)
                        except Exception as fence_error:
                            await _compensate_lost_execution([embedding_id], execution_token)
                            raise ExecutionFenceLost("Knowledge task execution lost after Milvus write") from fence_error
                        await mark_synced(chunk, embedding_id, len(vector))
                        result["synced"] += 1
                    except Exception as item_error:
                        await mark_failed(chunk, item_error)
                        result["failed"] += 1
                        result["failures"].append({"chunkId": chunk["id"], "message": str(item_error)[:300]})

        processed += len(batch)
        await java_client.record_knowledge_task_event(
            task_id,
            event_type="embedding_progress",
            title="向量处理进度",
            detail=f"已处理 {processed} / {len(candidates)}",
            progress_current=processed,
            progress_total=len(candidates),
        )

    await java_client.record_knowledge_task_event(
        task_id,
        event_type="embedding_completed",
        title="向量生成完成",
        detail=f"成功 {result['synced']}，失败 {result['failed']}，缓存命中 {result['cacheHits']}",
        progress_current=processed,
        progress_total=len(candidates),
        ok=result["failed"] == 0,
        error_code="EMBEDDING_PARTIAL_FAILED" if result["failed"] else None,
        suggestion="可在任务详情中重试失败 chunk" if result["failed"] else None,
    )
    if result["synced"] > 0:
        milvus_store.flush()
    return result


async def check_task_consistency(task_id: str) -> dict[str, Any]:
    chunks = await java_client.list_knowledge_task_chunks(task_id)
    checked = 0
    missing: list[int] = []
    for chunk in chunks:
        if chunk.get("status") == "REVIEW_REQUIRED":
            continue
        checked += 1
        embedding_id = str(chunk.get("embeddingId") or "")
        exists = bool(embedding_id) and milvus_store.has_embedding(embedding_id)
        if not exists and embedding_id:
            for delay in (0.2, 0.5, 1.0):
                await asyncio.sleep(delay)
                if milvus_store.has_embedding(embedding_id):
                    exists = True
                    break
        if not exists:
            missing.append(int(chunk["id"]))
    return {"checked": checked, "missing": len(missing), "missingChunkIds": missing}


async def mark_synced(chunk: dict[str, Any], embedding_id: str, vector_dimension: int) -> None:
    await java_client.update_chunk_embedding(
        int(chunk["id"]),
        embedding_id=embedding_id,
        embedding_model=settings.EMBEDDING_MODEL,
        embedding_model_version=settings.EMBEDDING_MODEL_VERSION,
        embedding_sync_status="SYNCED",
        vector_collection=settings.MILVUS_COLLECTION,
        status="READY_TO_PUBLISH",
    )
    await java_client.upsert_embedding_cache(
        content_hash=str(chunk.get("contentHash") or ""),
        embedding_model=settings.EMBEDDING_MODEL,
        embedding_id=embedding_id,
        vector_dimension=vector_dimension,
        retrieval_epoch=int(chunk.get("retrievalEpoch") or 0),
    )


async def mark_failed(chunk: dict[str, Any], error: Exception) -> None:
    await java_client.update_chunk_embedding(
        int(chunk["id"]),
        embedding_id=str(chunk.get("embeddingId") or "") or None,
        embedding_model=settings.EMBEDDING_MODEL,
        embedding_model_version=settings.EMBEDDING_MODEL_VERSION,
        embedding_sync_status="FAILED",
        vector_collection=settings.MILVUS_COLLECTION,
    )
