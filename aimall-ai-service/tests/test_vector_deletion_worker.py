import asyncio

from app.rag import vector_sync


class FakeJavaClient:
    def __init__(self):
        self.completed = []
        self.failed = []

    async def claim_vector_deletion_chunks(self, limit=100):
        return [{"id": 7, "embeddingId": "embedding-7", "vectorDeleteClaimToken": "claim-7"}]

    async def complete_vector_deletion(self, chunk_id, claim_token):
        self.completed.append((chunk_id, claim_token))
        return {"id": chunk_id, "embeddingSyncStatus": "DELETED"}

    async def fail_vector_deletion(self, chunk_id, claim_token, error_message):
        self.failed.append((chunk_id, claim_token, error_message))
        return {"id": chunk_id, "embeddingSyncStatus": "DELETE_PENDING"}


class FakeMilvusStore:
    available = True

    def __init__(self, deleted):
        self.deleted = deleted
        self.calls = []

    def delete_embedding(self, embedding_id):
        self.calls.append(embedding_id)
        return self.deleted


def test_vector_deletion_marks_java_deleted_only_after_milvus_confirmation(monkeypatch):
    java = FakeJavaClient()
    milvus = FakeMilvusStore(deleted=True)
    monkeypatch.setattr(vector_sync, "java_client", java)
    monkeypatch.setattr(vector_sync, "milvus_store", milvus)

    result = asyncio.run(vector_sync.process_pending_vector_deletions())

    assert result["deleted"] == 1
    assert result["failed"] == 0
    assert milvus.calls == ["embedding-7"]
    assert java.completed == [(7, "claim-7")]
    assert java.failed == []


def test_vector_deletion_keeps_pending_when_milvus_still_contains_vector(monkeypatch):
    java = FakeJavaClient()
    milvus = FakeMilvusStore(deleted=False)
    monkeypatch.setattr(vector_sync, "java_client", java)
    monkeypatch.setattr(vector_sync, "milvus_store", milvus)

    result = asyncio.run(vector_sync.process_pending_vector_deletions())

    assert result["deleted"] == 0
    assert result["failed"] == 1
    assert java.completed == []
    assert java.failed[0][0:2] == (7, "claim-7")


def test_stale_execution_compensates_vectors_before_any_chunk_callback(monkeypatch):
    class FenceJava:
        def __init__(self):
            self.fence_calls = 0
            self.chunk_callbacks = 0

        async def list_knowledge_task_chunks(self, task_id):
            return [{"id": 9, "docId": 1, "contentHash": "hash", "indexContent": "text", "status": "READY"}]

        async def record_knowledge_task_event(self, *args, **kwargs):
            return {}

        async def get_embedding_cache(self, *args, **kwargs):
            return {"hit": False}

        async def get_knowledge_task(self, task_id):
            self.fence_calls += 1
            if self.fence_calls <= 2:
                return {"status": "RUNNING", "executionToken": "attempt-1", "attemptNo": 1}
            raise RuntimeError("execution token is stale")

        async def update_chunk_embedding(self, *args, **kwargs):
            self.chunk_callbacks += 1

        async def upsert_embedding_cache(self, *args, **kwargs):
            return {}

    class FenceMilvus:
        available = True

        def __init__(self):
            self.compensated = []

        def get_vector(self, embedding_id):
            return None

        def delete_doc_version_embeddings_except_execution(self, doc_id, version_id, token):
            return None

        def upsert_chunks(self, items):
            return ["embedding-9"]

        def delete_embeddings_for_execution(self, ids, token):
            self.compensated.append((ids, token))
            return 1

        def flush(self):
            return None

    java = FenceJava()
    milvus = FenceMilvus()

    async def embed_batch(texts, **kwargs):
        return [[0.1] * vector_sync.settings.EMBEDDING_DIM]

    monkeypatch.setattr(vector_sync, "java_client", java)
    monkeypatch.setattr(vector_sync, "milvus_store", milvus)
    monkeypatch.setattr(vector_sync.embedding_provider, "embed_batch", embed_batch)

    try:
        asyncio.run(vector_sync.sync_task_vectors("KT-STALE"))
    except vector_sync.ExecutionFenceLost:
        pass
    else:
        raise AssertionError("stale execution must abort vector synchronization")

    assert milvus.compensated == [(["embedding-9"], "attempt-1")]
    assert java.chunk_callbacks == 0


def test_execution_fence_rejects_task_contract_without_execution_token(monkeypatch):
    class MissingTokenJava:
        async def get_knowledge_task(self, task_id):
            return {"status": "RUNNING", "attemptNo": 3}

    monkeypatch.setattr(vector_sync, "java_client", MissingTokenJava())

    try:
        asyncio.run(vector_sync._require_current_execution("KT-MISSING-TOKEN"))
    except vector_sync.ExecutionFenceLost as error:
        assert "omitted execution token" in str(error)
    else:
        raise AssertionError("task contract without execution token must not write Milvus")


def test_retry_rebuilds_synced_chunk_when_embedding_belongs_to_old_attempt(monkeypatch):
    class RetryJava:
        async def list_knowledge_task_chunks(self, task_id):
            return [{
                "id": 11, "docId": 1, "docVersionId": 2, "contentHash": "hash",
                "indexContent": "retry text", "status": "READY", "embeddingSyncStatus": "SYNCED",
                "embeddingId": "old-attempt-id",
            }]

        async def get_knowledge_task(self, task_id):
            return {"status": "RUNNING", "executionToken": "attempt-new", "attemptNo": 2, "docId": 1, "docVersionId": 2}

        async def record_knowledge_task_event(self, *args, **kwargs):
            return {}

        async def get_embedding_cache(self, *args, **kwargs):
            return {"hit": False}

        async def update_chunk_embedding(self, *args, **kwargs):
            return {}

        async def upsert_embedding_cache(self, *args, **kwargs):
            return {}

    class RetryMilvus:
        available = True

        def __init__(self):
            self.cleaned = []
            self.upserted = []

        def embedding_id(self, chunk):
            return "new-attempt-id" if chunk.get("executionToken") == "attempt-new" else "old-attempt-id"

        def delete_doc_version_embeddings_except_execution(self, doc_id, version_id, token):
            self.cleaned.append((doc_id, version_id, token))

        def get_vector(self, embedding_id):
            return None

        def upsert_chunks(self, items):
            self.upserted.extend(items)
            return ["new-attempt-id"]

        def flush(self):
            return None

    java = RetryJava()
    milvus = RetryMilvus()

    async def embed_batch(texts, **kwargs):
        return [[0.1] * vector_sync.settings.EMBEDDING_DIM]

    monkeypatch.setattr(vector_sync, "java_client", java)
    monkeypatch.setattr(vector_sync, "milvus_store", milvus)
    monkeypatch.setattr(vector_sync.embedding_provider, "embed_batch", embed_batch)

    result = asyncio.run(vector_sync.sync_task_vectors("KT-RETRY"))

    assert result["synced"] == 1
    assert milvus.cleaned == [(1, 2, "attempt-new")]
    assert milvus.upserted[0][0]["executionToken"] == "attempt-new"
