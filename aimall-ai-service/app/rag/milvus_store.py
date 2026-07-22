import hashlib
import re
import threading
from typing import Any

from app.config.settings import settings

try:
    from pymilvus import DataType, MilvusClient
except Exception:  # pragma: no cover - exercised when dependency is absent locally
    DataType = None
    MilvusClient = None


class MilvusVectorStore:
    def __init__(self) -> None:
        self.collection = settings.MILVUS_COLLECTION
        self.dim = settings.EMBEDDING_DIM
        self._client: Any | None = None
        self._client_lock = threading.RLock()

    @property
    def available(self) -> bool:
        return MilvusClient is not None

    @property
    def client(self) -> Any:
        if MilvusClient is None:
            raise RuntimeError("pymilvus is not installed")
        if self._client is None:
            with self._client_lock:
                if self._client is None:
                    self._client = MilvusClient(
                        uri=settings.MILVUS_URI,
                        dedicated=True,
                        timeout=settings.MILVUS_CONNECT_TIMEOUT_SECONDS,
                    )
        return self._client

    def ensure_collection(self) -> None:
        client: Any | None = None
        try:
            client = self.client
            self._ensure_collection(client)
        except Exception:
            if client is not None:
                self._discard_client(client)
            self._ensure_collection(self.client)

    def _ensure_collection(self, client: Any) -> None:
        if client.has_collection(self.collection):
            return
        schema = client.create_schema(auto_id=False, enable_dynamic_field=True)
        schema.add_field(field_name="embedding_id", datatype=DataType.VARCHAR, is_primary=True, max_length=255)
        schema.add_field(field_name="vector", datatype=DataType.FLOAT_VECTOR, dim=self.dim)
        schema.add_field(field_name="chunk_id", datatype=DataType.INT64)
        schema.add_field(field_name="doc_id", datatype=DataType.INT64)
        schema.add_field(field_name="tenant_id", datatype=DataType.VARCHAR, max_length=64)
        schema.add_field(field_name="visibility_scope", datatype=DataType.VARCHAR, max_length=64)
        schema.add_field(field_name="status", datatype=DataType.VARCHAR, max_length=32)
        schema.add_field(field_name="index_version", datatype=DataType.VARCHAR, max_length=64)
        schema.add_field(field_name="content_hash", datatype=DataType.VARCHAR, max_length=64)

        index_params = client.prepare_index_params()
        index_params.add_index(
            field_name="vector",
            index_type="AUTOINDEX",
            metric_type="COSINE",
        )
        client.create_collection(
            collection_name=self.collection,
            schema=schema,
            index_params=index_params,
        )
        client.load_collection(self.collection)

    def _discard_client(self, failed_client: Any) -> None:
        with self._client_lock:
            if self._client is failed_client:
                self._client = None
                close = getattr(failed_client, "close", None)
                if callable(close):
                    try:
                        close()
                    except Exception:
                        pass

    def upsert_chunk(self, chunk: dict[str, Any], vector: list[float]) -> str:
        return self.upsert_chunks([(chunk, vector)])[0]

    def upsert_chunks(self, items: list[tuple[dict[str, Any], list[float]]]) -> list[str]:
        if not items:
            return []
        self.ensure_collection()
        rows: list[dict[str, Any]] = []
        embedding_ids: list[str] = []
        for chunk, vector in items:
            if len(vector) != self.dim:
                raise RuntimeError(f"Embedding dimension mismatch: expected {self.dim}, got {len(vector)}")
            embedding_id = self.embedding_id(chunk)
            embedding_ids.append(embedding_id)
            role_access = self._role_access_flags(str(chunk.get("roleScope") or ""))
            rows.append(
                {
                    "embedding_id": embedding_id,
                    "vector": vector,
                    "chunk_id": int(chunk["id"]),
                    "doc_id": int(chunk["docId"]),
                    "doc_version_id": int(chunk.get("docVersionId") or 0),
                    "tenant_id": str(chunk.get("tenantId") or "default"),
                    "visibility_scope": str(chunk.get("visibilityScope") or "PUBLIC_USER"),
                    "role_scope": str(chunk.get("roleScope") or ""),
                    **role_access,
                    "category_ids": str(chunk.get("categoryIds") or ""),
                    "effective_time": str(chunk.get("effectiveTime") or ""),
                    "expire_time": str(chunk.get("expireTime") or ""),
                    "is_deleted": False,
                    "status": str(chunk.get("status") or "INDEXING"),
                    "index_version": str(chunk.get("indexVersion") or "index-v1"),
                    "content_hash": str(chunk.get("contentHash") or ""),
                    "execution_token": str(chunk.get("executionToken") or ""),
                    "attempt_no": int(chunk.get("attemptNo") or 0),
                    "publication_version": str(chunk.get("publicationVersion") or ""),
                    "retrieval_epoch": int(chunk.get("retrievalEpoch") or 0),
                }
            )
        self.client.upsert(collection_name=self.collection, data=rows)
        return embedding_ids

    def _role_access_flags(self, role_scope: str) -> dict[str, bool]:
        roles = {
            item
            for item in re.split(r"[,;|\s]+", role_scope.strip().upper())
            if item
        }
        if not roles:
            return {"role_public": True, "role_member": True, "role_admin": True}
        public_roles = {"PUBLIC_USER", "PUBLIC"}
        member_roles = public_roles | {"MEMBER", "AUTH_USER", "AUTHENTICATED"}
        admin_roles = member_roles | {"ADMIN", "ADMIN_ONLY", "PRIVATE"}
        return {
            "role_public": bool(roles & public_roles),
            "role_member": bool(roles & member_roles),
            "role_admin": bool(roles & admin_roles),
        }

    def has_embedding(self, embedding_id: str) -> bool:
        self.ensure_collection()
        result = self.client.query(
            collection_name=self.collection,
            filter=f'embedding_id == "{embedding_id}"',
            output_fields=["embedding_id"],
            limit=1,
        )
        return bool(result)

    def get_vector(self, embedding_id: str) -> list[float] | None:
        self.ensure_collection()
        result = self.client.query(
            collection_name=self.collection,
            filter=f'embedding_id == "{embedding_id}"',
            output_fields=["vector"],
            limit=1,
        )
        if not result or not isinstance(result[0].get("vector"), list):
            return None
        return [float(item) for item in result[0]["vector"]]

    def delete_embedding(self, embedding_id: str) -> bool:
        if not embedding_id:
            return True
        self.ensure_collection()
        self.client.delete(collection_name=self.collection, ids=[embedding_id])
        self.flush()
        return not self.has_embedding(embedding_id)

    def delete_embeddings_for_execution(self, embedding_ids: list[str], execution_token: str) -> int:
        """Remove only vectors that belong to the stale execution attempt."""
        if not embedding_ids or not execution_token:
            return 0
        self.ensure_collection()
        escaped = execution_token.replace('"', '\\"')
        deleted = 0
        for embedding_id in embedding_ids:
            escaped_id = embedding_id.replace('"', '\\"')
            result = self.client.delete(
                collection_name=self.collection,
                filter=f'embedding_id == "{escaped_id}" and execution_token == "{escaped}"',
            )
            if isinstance(result, dict):
                deleted += int(result.get("delete_count") or result.get("deleteCount") or 0)
        if deleted:
            self.flush()
        return deleted

    def update_doc_version_status_for_execution(
        self,
        doc_id: int,
        doc_version_id: int,
        execution_token: str,
        status: str,
        is_deleted: bool,
    ) -> int:
        if not execution_token:
            raise ValueError("execution token is required for fenced vector status updates")
        escaped = execution_token.replace('"', '\\"')
        return self._update_status(
            f"doc_id == {int(doc_id)} and doc_version_id == {int(doc_version_id)} and execution_token == \"{escaped}\"",
            status,
            is_deleted,
        )

    def get_doc_version_embeddings_for_execution(
        self, doc_id: int, doc_version_id: int, execution_token: str
    ) -> list[str]:
        if not execution_token:
            return []
        self.ensure_collection()
        escaped = execution_token.replace('"', '\\"')
        embedding_ids: list[str] = []
        for rows in self._query_batches(
            f"doc_id == {int(doc_id)} and doc_version_id == {int(doc_version_id)} and execution_token == \"{escaped}\"",
            ["embedding_id"],
        ):
            embedding_ids.extend(str(row.get("embedding_id")) for row in rows if row.get("embedding_id"))
        return embedding_ids

    def delete_doc_version_embeddings_except_execution(
        self, doc_id: int, doc_version_id: int, execution_token: str
    ) -> None:
        """New attempts remove abandoned vectors before writing their own physical IDs."""
        if not execution_token:
            raise ValueError("execution token is required for stale vector cleanup")
        self.ensure_collection()
        escaped = execution_token.replace('"', '\\"')
        self.client.delete(
            collection_name=self.collection,
            filter=f"doc_id == {int(doc_id)} and doc_version_id == {int(doc_version_id)} and execution_token != \"{escaped}\"",
        )
        self.flush()

    def flush(self) -> None:
        self.ensure_collection()
        self.client.flush(collection_name=self.collection)

    def search(
        self,
        vector: list[float],
        limit: int = 3,
        filter_expression: str | None = None,
    ) -> list[dict[str, Any]]:
        self.ensure_collection()
        search_args: dict[str, Any] = {
            "collection_name": self.collection,
            "data": [vector],
            "limit": max(1, min(limit, 100)),
            "output_fields": [
                "embedding_id",
                "chunk_id",
                "doc_id",
                "doc_version_id",
                "tenant_id",
                "visibility_scope",
                "role_scope",
                "category_ids",
                "effective_time",
                "expire_time",
                "is_deleted",
                "status",
                "content_hash",
                "publication_version",
                "retrieval_epoch",
            ],
            "search_params": {"metric_type": "COSINE"},
        }
        if filter_expression:
            search_args["filter"] = filter_expression
        result = self.client.search(
            **search_args,
        )
        if not result:
            return []
        return [self._normalize_search_hit(item) for item in result[0]]

    def _normalize_search_hit(self, item: Any) -> dict[str, Any]:
        hit = dict(item)
        entity = hit.pop("entity", None)
        if isinstance(entity, dict):
            hit.update(entity)
        if "score" not in hit and "distance" in hit:
            hit["score"] = hit["distance"]
        return hit

    def update_doc_status(self, doc_id: int, status: str, is_deleted: bool,
                          publication_version: str | None = None,
                          retrieval_epoch: int | None = None) -> int:
        return self._update_status(
            f"doc_id == {int(doc_id)}", status, is_deleted, publication_version, retrieval_epoch
        )

    def update_doc_version_status(
        self,
        doc_id: int,
        doc_version_id: int,
        status: str,
        is_deleted: bool,
        publication_version: str | None = None,
        retrieval_epoch: int | None = None,
    ) -> int:
        return self._update_status(
            f"doc_id == {int(doc_id)} and doc_version_id == {int(doc_version_id)}",
            status,
            is_deleted,
            publication_version,
            retrieval_epoch,
        )

    def get_doc_version_status(self, doc_id: int, doc_version_id: int) -> dict[str, Any]:
        self.ensure_collection()
        count = 0
        statuses: set[str] = set()
        any_active = False
        all_active = True
        for rows in self._query_batches(
            f"doc_id == {int(doc_id)} and doc_version_id == {int(doc_version_id)}",
            ["status", "is_deleted"],
        ):
            count += len(rows)
            for row in rows:
                status = str(row.get("status") or "")
                active = status.upper() == "ACTIVE" and not bool(row.get("is_deleted"))
                statuses.add(status)
                any_active = any_active or active
                all_active = all_active and active
        return {
            "found": count > 0,
            "vectorCount": count,
            "statuses": sorted(statuses),
            "anyActive": any_active,
            "allActive": count > 0 and all_active,
        }

    def _update_status(self, filter_expression: str, status: str, is_deleted: bool,
                       publication_version: str | None = None,
                       retrieval_epoch: int | None = None) -> int:
        self.ensure_collection()
        updated_count = 0
        for rows in self._query_batches(filter_expression, ["*"]):
            updated: list[dict[str, Any]] = []
            for item in rows:
                row = dict(item)
                row["status"] = status
                row["is_deleted"] = is_deleted
                if publication_version is not None:
                    row["publication_version"] = publication_version
                if retrieval_epoch is not None:
                    row["retrieval_epoch"] = int(retrieval_epoch)
                updated.append(row)
            if updated:
                self.client.upsert(collection_name=self.collection, data=updated)
                updated_count += len(updated)
        if updated_count:
            self.flush()
        return updated_count

    def _query_batches(
        self,
        filter_expression: str,
        output_fields: list[str],
        batch_size: int = 1000,
    ):
        query_iterator = getattr(self.client, "query_iterator", None)
        if callable(query_iterator):
            iterator = query_iterator(
                collection_name=self.collection,
                filter=filter_expression,
                output_fields=output_fields,
                batch_size=batch_size,
            )
            try:
                while True:
                    rows = iterator.next()
                    if not rows:
                        break
                    yield rows
            finally:
                close = getattr(iterator, "close", None)
                if callable(close):
                    close()
            return
        offset = 0
        while True:
            rows = self.client.query(
                collection_name=self.collection,
                filter=filter_expression,
                output_fields=output_fields,
                limit=batch_size,
                offset=offset,
            )
            if not rows:
                break
            yield rows
            if len(rows) < batch_size:
                break
            offset += len(rows)

    def health(self) -> dict[str, Any]:
        if not self.available:
            return {
                "enabled": False,
                "status": "DOWN",
                "connectionMode": "DEDICATED_RECONNECT_V1",
                "message": "pymilvus is not installed",
            }
        client: Any | None = None
        try:
            client = self.client
            return self._health_with_client(client)
        except Exception:
            if client is not None:
                self._discard_client(client)
        try:
            return self._health_with_client(self.client)
        except Exception as exc:
            return {
                "enabled": True,
                "status": "DOWN",
                "connectionMode": "DEDICATED_RECONNECT_V1",
                "uri": settings.MILVUS_URI,
                "collection": self.collection,
                "message": str(exc),
            }

    def _health_with_client(self, client: Any) -> dict[str, Any]:
        has_collection = client.has_collection(self.collection)
        row_count = 0
        if has_collection:
            try:
                stats = client.get_collection_stats(collection_name=self.collection)
                row_count = int(stats.get("row_count") or stats.get("rowCount") or 0)
            except Exception:
                row_count = -1
        return {
            "enabled": True,
            "status": "UP",
            "connectionMode": "DEDICATED_RECONNECT_V1",
            "uri": settings.MILVUS_URI,
            "collection": self.collection,
            "collectionExists": has_collection,
            "rowCount": row_count,
        }

    def embedding_id(self, chunk: dict[str, Any]) -> str:
        chunk_key = str(chunk.get("chunkKey") or chunk.get("id"))
        content_hash = str(chunk.get("contentHash") or "")
        execution_token = str(chunk.get("executionToken") or "")
        if not execution_token:
            return f"{chunk_key}:{settings.EMBEDDING_MODEL_VERSION}:{content_hash[:16]}"
        attempt_key = hashlib.sha256(execution_token.encode("utf-8")).hexdigest()[:16]
        return f"{chunk_key}:{settings.EMBEDDING_MODEL_VERSION}:{content_hash[:16]}:{attempt_key}"


milvus_store = MilvusVectorStore()
