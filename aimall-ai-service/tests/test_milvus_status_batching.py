from app.rag.milvus_store import MilvusVectorStore


class FakeIterator:
    def __init__(self, rows, batch_size):
        self.rows = rows
        self.batch_size = batch_size
        self.offset = 0
        self.closed = False

    def next(self):
        batch = self.rows[self.offset : self.offset + self.batch_size]
        self.offset += len(batch)
        return [dict(row) for row in batch]

    def close(self):
        self.closed = True


class FakeMilvusClient:
    def __init__(self, count=10050):
        self.rows = [
            {
                "embedding_id": f"embedding-{index}",
                "doc_id": 1,
                "doc_version_id": 2,
                "status": "READY",
                "is_deleted": False,
            }
            for index in range(count)
        ]
        self.upserted = 0

    def has_collection(self, _collection):
        return True

    def query_iterator(self, *, batch_size, **_kwargs):
        return FakeIterator(self.rows, batch_size)

    def upsert(self, *, data, **_kwargs):
        by_id = {row["embedding_id"]: row for row in self.rows}
        for row in data:
            by_id[row["embedding_id"]].update(row)
        self.upserted += len(data)

    def flush(self, **_kwargs):
        return None


def test_status_update_and_query_cover_more_than_ten_thousand_vectors():
    store = MilvusVectorStore()
    client = FakeMilvusClient()
    store._client = client

    updated = store.update_doc_version_status(1, 2, "ACTIVE", False)
    status = store.get_doc_version_status(1, 2)

    assert updated == 10050
    assert client.upserted == 10050
    assert status["vectorCount"] == 10050
    assert status["allActive"] is True


def test_stale_execution_compensation_uses_single_atomic_milvus_filter():
    class DeleteClient:
        def __init__(self):
            self.delete_calls = []

        def has_collection(self, _collection):
            return True

        def delete(self, **kwargs):
            self.delete_calls.append(kwargs)
            return {"delete_count": 1}

        def flush(self, **_kwargs):
            return None

    store = MilvusVectorStore()
    client = DeleteClient()
    store._client = client

    assert store.delete_embeddings_for_execution(["embedding-1"], "attempt-1") == 1
    assert len(client.delete_calls) == 1
    assert client.delete_calls[0]["filter"] == 'embedding_id == "embedding-1" and execution_token == "attempt-1"'


def test_attempts_use_distinct_physical_embedding_ids():
    store = MilvusVectorStore()
    base = {"id": 7, "chunkKey": "chunk-7", "contentHash": "abc123"}

    assert store.embedding_id({**base, "executionToken": "attempt-a"}) != store.embedding_id(
        {**base, "executionToken": "attempt-b"}
    )


def test_execution_embedding_lookup_pages_past_previous_fixed_limit():
    store = MilvusVectorStore()
    client = FakeMilvusClient(count=17050)
    for row in client.rows:
        row["execution_token"] = "attempt-1"
    store._client = client

    embedding_ids = store.get_doc_version_embeddings_for_execution(1, 2, "attempt-1")

    assert len(embedding_ids) == 17050


def test_review_required_update_is_scoped_to_new_version_and_keeps_old_active():
    class VersionScopedClient(FakeMilvusClient):
        def __init__(self):
            super().__init__(count=0)
            self.rows = [
                {
                    "embedding_id": "old-active",
                    "doc_id": 1,
                    "doc_version_id": 1,
                    "execution_token": "published",
                    "status": "ACTIVE",
                    "is_deleted": False,
                },
                {
                    "embedding_id": "new-draft",
                    "doc_id": 1,
                    "doc_version_id": 2,
                    "execution_token": "attempt-2",
                    "status": "READY",
                    "is_deleted": False,
                },
            ]

        def query_iterator(self, *, filter, batch_size, **_kwargs):
            version = 1 if "doc_version_id == 1" in filter else 2
            rows = [row for row in self.rows if row["doc_version_id"] == version]
            if 'execution_token == "attempt-2"' in filter:
                rows = [row for row in rows if row["execution_token"] == "attempt-2"]
            return FakeIterator(rows, batch_size)

    store = MilvusVectorStore()
    client = VersionScopedClient()
    store._client = client

    updated = store.update_doc_version_status_for_execution(1, 2, "attempt-2", "REVIEW_REQUIRED", False)

    assert updated == 1
    assert next(row for row in client.rows if row["doc_version_id"] == 1)["status"] == "ACTIVE"
    assert next(row for row in client.rows if row["doc_version_id"] == 2)["status"] == "REVIEW_REQUIRED"
