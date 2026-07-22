import asyncio

from app.api import vector_api
from app.rag import vector_sync


class _Java:
    def __init__(self, chunks, stale_after_write=False):
        self.chunks = chunks
        self.stale_after_write = stale_after_write
        self.task_reads = 0
        self.bound = []
        self.updated = []

    async def list_vector_sync_chunks(self, **_kwargs):
        return self.chunks

    async def get_knowledge_task(self, _task_id):
        self.task_reads += 1
        if self.stale_after_write and self.task_reads >= 3:
            raise RuntimeError("stale attempt")
        return {"status": "RUNNING", "executionToken": "attempt-1", "attemptNo": 1, "docId": 1, "docVersionId": 2}

    def bind_knowledge_execution(self, task_id, token):
        self.bound.append((task_id, token))
        return object()

    def reset_knowledge_execution(self, _context):
        return None

    async def update_chunk_embedding(self, chunk_id, **kwargs):
        self.updated.append((chunk_id, kwargs))
        return {}


class _Milvus:
    available = True

    def __init__(self):
        self.upserted = []
        self.compensated = []
        self.cleaned = []

    def delete_doc_version_embeddings_except_execution(self, *args):
        self.cleaned.append(args)

    def upsert_chunk(self, chunk, vector):
        self.upserted.append((chunk, vector))
        return "embedding-attempt-1"

    def delete_embeddings_for_execution(self, ids, token):
        self.compensated.append((ids, token))
        return 1

    def embedding_id(self, chunk):
        return "embedding-attempt-1"

    def has_embedding(self, _embedding_id):
        return False


def _chunk():
    return {
        "id": 8, "docId": 1, "docVersionId": 2, "indexContent": "text", "maskedContent": "text",
        "contentHash": "hash", "executionTaskId": "KT-1", "executionToken": "attempt-1",
        "embeddingId": "old-embedding", "embeddingModelVersion": "old-model",
    }


def test_vector_sync_compensates_when_maintenance_execution_loses_fence(monkeypatch):
    java = _Java([_chunk()], stale_after_write=True)
    milvus = _Milvus()

    async def embed(_text):
        return [0.1] * vector_api.settings.EMBEDDING_DIM

    async def no_deletions(**_kwargs):
        return {"total": 0, "deleted": 0, "failed": 0}

    monkeypatch.setattr(vector_api, "java_client", java)
    monkeypatch.setattr(vector_sync, "java_client", java)
    monkeypatch.setattr(vector_api, "milvus_store", milvus)
    monkeypatch.setattr(vector_sync, "milvus_store", milvus)
    monkeypatch.setattr(vector_api.embedding_provider, "embed", embed)
    monkeypatch.setattr(vector_api, "process_pending_vector_deletions", no_deletions)

    result = asyncio.run(vector_api.sync_vectors())

    assert result["data"]["synced"] == 0
    assert result["data"]["failed"] == 1
    assert milvus.compensated == [(["embedding-attempt-1"], "attempt-1")]
    assert java.updated == []


def test_consistency_check_binds_execution_before_rescheduling(monkeypatch):
    java = _Java([_chunk()])
    milvus = _Milvus()

    monkeypatch.setattr(vector_api, "java_client", java)
    monkeypatch.setattr(vector_sync, "java_client", java)
    monkeypatch.setattr(vector_api, "milvus_store", milvus)

    result = asyncio.run(vector_api.consistency_check())

    assert result["data"]["rescheduled"] == 1
    assert java.bound == [("KT-1", "attempt-1")]
    assert java.updated[0][0] == 8
    assert java.updated[0][1]["embedding_sync_status"] == "PENDING"
