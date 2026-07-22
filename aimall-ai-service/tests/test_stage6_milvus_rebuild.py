import asyncio

import pytest

from app.evaluation.stage6_milvus_rebuild import verify_business_rebuild


class FakeJavaClient:
    def __init__(self, status="SUCCESS", chunks=None):
        self.status = status
        self.chunks = chunks

    async def rebuild_knowledge_chunks(self):
        return {
            "docCount": 1,
            "taskCount": 1,
            "tasks": [{"taskId": "task-1", "docId": 7, "docVersionId": 9, "status": "PENDING"}],
        }

    async def get_knowledge_task_acceptance(self, task_id):
        if self.chunks is not None:
            chunks = self.chunks
        else:
            chunks = [{"id": 11, "status": "READY_TO_PUBLISH", "embeddingId": "embedding-11"}]
        return {"taskId": task_id, "status": self.status, "chunks": chunks}


class FakeVectorStore:
    def __init__(self, present=True):
        self.present = present

    def has_embedding(self, _embedding_id):
        return self.present


def test_business_milvus_rebuild_waits_and_verifies_physical_vectors():
    report = asyncio.run(verify_business_rebuild(FakeJavaClient(), FakeVectorStore(), poll_seconds=0))

    assert report["passed"] is True
    assert report["completedTasks"] == 1
    assert report["checkedChunks"] == 1


def test_business_milvus_rebuild_fails_closed_for_task_or_vector_failure():
    with pytest.raises(RuntimeError, match="DEAD_LETTER"):
        asyncio.run(verify_business_rebuild(FakeJavaClient("DEAD_LETTER"), FakeVectorStore(), poll_seconds=0))

    report = asyncio.run(
        verify_business_rebuild(FakeJavaClient(), FakeVectorStore(present=False), poll_seconds=0)
    )
    assert report["passed"] is False
    assert report["missingChunks"][0]["chunkId"] == 11

    empty = asyncio.run(
        verify_business_rebuild(FakeJavaClient(chunks=[]), FakeVectorStore(), poll_seconds=0)
    )
    assert empty["passed"] is False
    assert empty["emptyTaskIds"] == ["task-1"]
