from __future__ import annotations

import asyncio
import time
from typing import Any


TERMINAL_FAILURES = {"FAILED", "PARTIAL_FAILED", "DEAD_LETTER", "CLOSED"}


async def verify_business_rebuild(
    java_client,
    vector_store,
    *,
    timeout_seconds: float = 900,
    poll_seconds: float = 2,
    allow_empty: bool = False,
) -> dict[str, Any]:
    trigger = await java_client.rebuild_knowledge_chunks()
    raw_tasks = trigger.get("tasks") if isinstance(trigger, dict) else None
    if not isinstance(raw_tasks, list):
        raise RuntimeError("Knowledge rebuild response omitted tasks")

    expected_doc_count = int(trigger.get("docCount") or 0)
    expected_task_count = int(trigger.get("taskCount") or 0)
    if expected_task_count != len(raw_tasks) or expected_doc_count != len(raw_tasks):
        raise RuntimeError("Knowledge rebuild did not return exactly one task per current document version")

    task_ids = list(dict.fromkeys(str(item.get("taskId") or "") for item in raw_tasks))
    task_ids = [task_id for task_id in task_ids if task_id]
    if len(task_ids) != len(raw_tasks):
        raise RuntimeError("Knowledge rebuild returned missing or duplicate task IDs")
    if not task_ids and not allow_empty:
        raise RuntimeError("Knowledge rebuild found no current document versions")

    deadline = time.monotonic() + timeout_seconds
    final_tasks: dict[str, dict[str, Any]] = {}
    pending = set(task_ids)
    while pending:
        for task_id in list(pending):
            task = await java_client.get_knowledge_task_acceptance(task_id)
            status = str(task.get("status") or "")
            if status in TERMINAL_FAILURES:
                raise RuntimeError(
                    f"Knowledge rebuild task {task_id} failed: {status} "
                    f"{str(task.get('errorCode') or '')} {str(task.get('errorMessage') or '')}".strip()
                )
            if status == "SUCCESS":
                final_tasks[task_id] = task
                pending.remove(task_id)
        if pending:
            if time.monotonic() >= deadline:
                raise TimeoutError(f"Knowledge rebuild timed out; pending tasks: {sorted(pending)}")
            await asyncio.sleep(poll_seconds)

    checked = 0
    missing: list[dict[str, Any]] = []
    review_blocked: list[int] = []
    empty_task_ids: list[str] = []
    for task_id in task_ids:
        chunks = final_tasks[task_id].get("chunks") or []
        if not chunks:
            empty_task_ids.append(task_id)
        for chunk in chunks:
            if str(chunk.get("status") or "") == "REVIEW_REQUIRED":
                review_blocked.append(int(chunk["id"]))
                continue
            checked += 1
            embedding_id = str(chunk.get("embeddingId") or "")
            if not embedding_id or not vector_store.has_embedding(embedding_id):
                missing.append({"taskId": task_id, "chunkId": chunk.get("id"), "embeddingId": embedding_id})

    return {
        "schemaVersion": "AIMALL_STAGE6_MILVUS_REBUILD_V1",
        "trigger": trigger,
        "taskIds": task_ids,
        "completedTasks": len(final_tasks),
        "checkedChunks": checked,
        "missingChunks": missing,
        "reviewBlockedChunkIds": review_blocked,
        "emptyTaskIds": empty_task_ids,
        "passed": (
            not missing
            and not review_blocked
            and not empty_task_ids
            and (bool(task_ids) or allow_empty)
        ),
    }
