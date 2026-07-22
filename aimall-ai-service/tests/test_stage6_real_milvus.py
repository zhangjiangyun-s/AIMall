import os
import uuid
from datetime import datetime, timedelta, timezone

import pytest

from app.rag.milvus_store import MilvusVectorStore
from app.tools.java_client import JavaClient


pytestmark = pytest.mark.skipif(
    os.getenv("RUN_REAL_MILVUS_TESTS") != "1",
    reason="set RUN_REAL_MILVUS_TESTS=1 to run Milvus scope acceptance",
)


def test_real_milvus_enforces_tenant_role_and_effective_window():
    store = MilvusVectorStore()
    store.collection = f"aimall_stage6_{uuid.uuid4().hex}"
    store.dim = 4
    now = datetime.now(timezone.utc)

    def instant(value):
        return value.isoformat(timespec="seconds").replace("+00:00", "Z")

    def chunk(chunk_id, tenant, role_scope, effective, expires):
        return {
            "id": chunk_id,
            "docId": chunk_id,
            "docVersionId": chunk_id,
            "chunkKey": f"chunk-{chunk_id}",
            "contentHash": f"hash-{chunk_id}",
            "tenantId": tenant,
            "visibilityScope": "PUBLIC_USER" if role_scope == "PUBLIC" else "ADMIN_ONLY",
            "roleScope": role_scope,
            "effectiveTime": instant(effective),
            "expireTime": instant(expires),
            "status": "ACTIVE",
        }

    try:
        store.upsert_chunks([
            (chunk(1, "tenant-a", "PUBLIC", now - timedelta(days=1), now + timedelta(days=1)), [1, 0, 0, 0]),
            (chunk(2, "tenant-a", "ADMIN_ONLY", now - timedelta(days=1), now + timedelta(days=1)), [1, 0, 0, 0]),
            (chunk(3, "tenant-b", "PUBLIC", now - timedelta(days=1), now + timedelta(days=1)), [1, 0, 0, 0]),
            (chunk(4, "tenant-a", "PUBLIC", now - timedelta(days=2), now - timedelta(days=1)), [1, 0, 0, 0]),
            (chunk(5, "tenant-a", "PUBLIC", now + timedelta(days=1), now + timedelta(days=2)), [1, 0, 0, 0]),
        ])
        store.flush()
        client = JavaClient()

        public_hits = store.search(
            [1, 0, 0, 0], 10,
            client._milvus_scope_filter({"tenantId": "tenant-a", "role": "PUBLIC_USER"}),
        )
        admin_hits = store.search(
            [1, 0, 0, 0], 10,
            client._milvus_scope_filter({"tenantId": "tenant-a", "role": "ADMIN"}),
        )

        assert {item["chunk_id"] for item in public_hits} == {1}
        assert {item["chunk_id"] for item in admin_hits} == {1, 2}
    finally:
        if store._client is not None and store.client.has_collection(store.collection):
            store.client.drop_collection(store.collection)
