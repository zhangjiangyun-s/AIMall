import asyncio
from datetime import datetime, timezone
import hashlib
import hmac
import json
import re
import time
import uuid
from contextvars import ContextVar
from typing import Any
from urllib.parse import urlencode

import httpx

from app.config.settings import normalize_rag_retrieval_mode, settings
from app.guardrails import guardrail_service
from app.rag.embedding import embedding_provider
from app.rag.milvus_store import milvus_store
from app.observability.trace_context import current_trace_id


class JavaClient:
    """HTTP client for controlled calls to aimall-server business APIs."""

    _POLICY_DOMAIN_TERMS = (
        "七天无理由",
        "订单有效期",
        "退款到账",
        "审核通过",
        "普通现货",
        "自动关闭",
        "原路退回",
        "发货时间",
        "申请时效",
        "购物车",
        "待支付",
        "未支付",
        "已完成",
        "现货",
        "商品",
        "发货",
        "时效",
        "配送",
        "物流",
        "运费",
        "价格",
        "锁定",
        "库存",
        "下单",
        "订单",
        "取消",
        "关闭",
        "恢复",
        "重新下单",
        "支付",
        "退款",
        "到账",
        "寄回",
        "质检",
        "退货",
        "换货",
        "售后",
        "优惠券",
        "满减",
        "发票",
        "签收",
        "地址",
    )
    _POLICY_QUERY_FILLERS = (
        "并给出引用依据",
        "给出引用依据",
        "引用依据",
        "请说明",
        "AIMALL",
        "平台",
        "商城",
        "相关",
        "具体",
        "规则",
        "政策",
        "规定",
        "说明",
        "是什么",
        "有哪些",
        "怎么",
        "如何",
        "为什么",
        "请问",
        "给出",
        "引用",
        "依据",
        "请",
        "并",
        "多久",
        "什么时候",
        "是否",
        "可以",
        "能否",
        "后",
        "吗",
        "呢",
        "的",
    )
    _POLICY_TOPIC_TERMS = (
        "购物车",
        "发货",
        "配送",
        "物流",
        "运费",
        "退货",
        "退款",
        "换货",
        "售后",
        "优惠券",
        "发票",
        "支付",
        "取消",
        "库存",
        "价格",
        "签收",
    )

    def __init__(self, base_url: str | None = None, timeout: int | None = None):
        self.base_url = (base_url or settings.AIMALL_SERVER_BASE_URL).rstrip("/")
        self.timeout = timeout or settings.TOOL_TIMEOUT
        self._client: httpx.AsyncClient | None = None
        self._knowledge_execution: ContextVar[tuple[str, str] | None] = ContextVar(
            "knowledge_execution", default=None
        )

    def bind_knowledge_execution(self, task_id: str, execution_token: str):
        if not execution_token:
            raise ValueError("executionToken is required")
        return self._knowledge_execution.set((task_id, execution_token))

    def reset_knowledge_execution(self, context_token) -> None:
        self._knowledge_execution.reset(context_token)

    @property
    def client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(timeout=self.timeout)
        return self._client

    async def close(self) -> None:
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()

    async def _get_data(
        self,
        path: str,
        params: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
    ) -> Any:
        request_params = dict(params or {})
        execution = self._knowledge_execution.get()
        if execution and path.startswith("/internal/ai/knowledge/"):
            request_params["executionTaskId"] = execution[0]
            request_params["executionToken"] = execution[1]
        query = self._canonical_query(request_params)
        url = f"{self.base_url}{path}" + (f"?{query}" if query else "")
        response = await self.client.get(
            url,
            headers=self._request_headers("GET", path, query, b"", headers),
        )
        response.raise_for_status()
        payload = response.json()
        if payload.get("code") != 0:
            raise RuntimeError(payload.get("message") or "aimall-server returned failed response")
        return payload.get("data")

    async def _post_data(
        self,
        path: str,
        body: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
    ) -> Any:
        request_body = dict(body or {})
        execution = self._knowledge_execution.get()
        if execution and path.startswith("/internal/ai/knowledge/"):
            request_body["executionTaskId"] = execution[0]
            request_body["executionToken"] = execution[1]
        body_bytes = json.dumps(
            request_body,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
        request_headers = self._request_headers("POST", path, "", body_bytes, headers)
        request_headers["Content-Type"] = "application/json"
        response = await self.client.post(
            f"{self.base_url}{path}",
            content=body_bytes,
            headers=request_headers,
        )
        response.raise_for_status()
        payload = response.json()
        if payload.get("code") != 0:
            raise RuntimeError(payload.get("message") or "aimall-server returned failed response")
        return payload.get("data")

    def _request_headers(
        self,
        method: str,
        path: str,
        query: str,
        body: bytes,
        headers: dict[str, str] | None = None,
    ) -> dict[str, str]:
        merged = dict(headers or {})
        trace_id = current_trace_id()
        if trace_id:
            merged["X-Trace-Id"] = trace_id
        if settings.AI_TO_JAVA_SECRET:
            timestamp = str(int(time.time()))
            nonce = uuid.uuid4().hex
            key_id = settings.AI_TO_JAVA_KEY_ID
            token = merged.get("token", "")
            body_hash = hashlib.sha256(body).hexdigest()
            token_hash = hashlib.sha256(token.encode("utf-8")).hexdigest()
            canonical = (
                f"{method.upper()}\n{path}\n{query}\n{body_hash}\n{token_hash}\n"
                f"{key_id}\n{timestamp}\n{nonce}"
            )
            signature = hmac.new(
                settings.AI_TO_JAVA_SECRET.encode("utf-8"),
                canonical.encode("utf-8"),
                hashlib.sha256,
            ).hexdigest()
            merged["X-AIMall-Key-Id"] = key_id
            merged["X-AIMall-Timestamp"] = timestamp
            merged["X-AIMall-Nonce"] = nonce
            merged["X-AIMall-Signature"] = signature
        return merged

    def _canonical_query(self, params: dict[str, Any] | None) -> str:
        if not params:
            return ""
        pairs: list[tuple[str, str]] = []
        for key in sorted(params):
            value = params[key]
            if isinstance(value, (list, tuple)):
                pairs.extend((key, str(item)) for item in value)
            elif value is not None:
                if isinstance(value, bool):
                    pairs.append((key, "true" if value else "false"))
                else:
                    pairs.append((key, str(value)))
        return urlencode(pairs)

    async def get_product_detail(self, product_id: int) -> dict[str, Any] | None:
        return await self._get_data(f"/internal/ai/products/{product_id}")

    async def get_product_skus(self, product_id: int) -> list[dict[str, Any]]:
        skus = await self._get_data(f"/internal/ai/products/{product_id}/skus")
        if not isinstance(skus, list):
            return []
        return skus

    async def compare_products(self, product_ids: list[int]) -> dict[str, Any]:
        data = await self._get_data("/internal/ai/products/compare", {"productIds": ",".join(str(item) for item in product_ids)})
        if not isinstance(data, dict):
            return {"products": []}
        return data

    async def search_products(
        self,
        keyword: str | None = None,
        category_id: int | None = None,
        min_price: float | None = None,
        max_price: float | None = None,
        in_stock: bool = True,
        limit: int = 10,
    ) -> list[dict[str, Any]]:
        params: dict[str, Any] = {
            "keyword": keyword or "",
            "inStock": in_stock,
            "limit": limit,
        }
        if category_id is not None:
            params["categoryId"] = category_id
        if min_price is not None:
            params["minPrice"] = min_price
        if max_price is not None:
            params["maxPrice"] = max_price

        products = await self._get_data("/internal/ai/products", params)
        if not isinstance(products, list):
            return []
        return products

    async def search_policy_kb(
        self,
        keyword: str | None = None,
        top_k: int = 5,
        source_type: str | None = None,
        auth_token: str | None = None,
        category_id: int | None = None,
    ) -> dict[str, Any]:
        mode = normalize_rag_retrieval_mode(settings.RAG_RETRIEVAL_MODE)
        retrieval_keyword = self._rewrite_policy_keyword(keyword)
        raw_docs = await self._search_policy_docs(
            retrieval_keyword, top_k, source_type, auth_token, category_id
        )
        docs, blocked_docs = guardrail_service.filter_evidence(raw_docs)
        raw_chunks, hybrid_meta = await self._search_policy_hybrid(
            retrieval_keyword, top_k, source_type, auth_token, category_id
        ) if mode in ("HYBRID", "VECTOR") else ([], None)
        chunks, blocked_chunks = guardrail_service.filter_evidence(raw_chunks)
        docs, doc_relevance = self._filter_policy_relevance(keyword, docs)
        chunks, chunk_relevance = self._filter_policy_relevance(keyword, chunks)
        evidence_guardrail = self._evidence_guardrail_metadata(blocked_docs, blocked_chunks)
        shadow = self._chunk_shadow_result(mode, chunks)
        if shadow is not None:
            shadow["hybrid"] = hybrid_meta
            shadow["relevanceValidation"] = chunk_relevance

        if mode == "VECTOR":
            return {
                "retrievalMode": mode,
                "primarySource": "chunk",
                "fallbackUsed": False,
                "documents": chunks,
                "evidence": chunks,
                "shadow": shadow,
                "retrievalStatus": "OK" if chunks else self._empty_evidence_status(blocked_chunks),
                "refusalReason": None if chunks else self._empty_evidence_reason(blocked_chunks),
                "evidenceGuardrail": evidence_guardrail,
                "relevanceValidation": chunk_relevance,
            }

        if mode == "HYBRID":
            if chunks:
                return {
                    "retrievalMode": mode,
                    "primarySource": "chunk",
                    "fallbackUsed": False,
                    "documents": chunks,
                    "evidence": chunks,
                    "shadow": shadow,
                    "retrievalStatus": "OK",
                    "refusalReason": None,
                    "evidenceGuardrail": evidence_guardrail,
                    "relevanceValidation": chunk_relevance,
                }
            return {
                "retrievalMode": mode,
                "primarySource": "doc",
                "fallbackUsed": True,
                "documents": docs,
                "evidence": docs,
                "shadow": shadow,
                "retrievalStatus": "DOC_FALLBACK",
                "refusalReason": None,
                "evidenceGuardrail": evidence_guardrail,
                "relevanceValidation": doc_relevance,
            } if docs else {
                "retrievalMode": mode,
                "primarySource": "none",
                "fallbackUsed": True,
                "documents": [],
                "evidence": [],
                "shadow": shadow,
                "retrievalStatus": self._empty_evidence_status([*blocked_chunks, *blocked_docs]),
                "refusalReason": self._empty_evidence_reason([*blocked_chunks, *blocked_docs]),
                "evidenceGuardrail": evidence_guardrail,
                "relevanceValidation": self._merge_relevance_validation(chunk_relevance, doc_relevance),
            }

        return {
            "retrievalMode": "DOC_ONLY",
            "primarySource": "doc",
            "fallbackUsed": False,
            "documents": docs,
            "evidence": docs,
            "shadow": None,
            "retrievalStatus": "OK" if docs else self._empty_evidence_status(blocked_docs),
            "refusalReason": None if docs else self._empty_evidence_reason(blocked_docs),
            "evidenceGuardrail": evidence_guardrail,
            "relevanceValidation": doc_relevance,
        }

    def _filter_policy_relevance(
        self,
        keyword: str | None,
        evidence: list[dict[str, Any]],
    ) -> tuple[list[dict[str, Any]], dict[str, Any]]:
        anchors = self._policy_query_anchors(keyword)
        topics = self._policy_topic_terms(keyword)
        if not evidence:
            return evidence, {
                "status": "NO_EVIDENCE",
                "anchors": anchors,
                "topics": topics,
                "inputCount": len(evidence),
                "keptCount": len(evidence),
            }

        kept = [
            item
            for item in evidence
            if (not anchors or self._evidence_matches_terms(item, anchors, allow_partial_anchor=True))
            and (not topics or self._evidence_matches_terms(item, topics))
        ]
        kept.sort(key=lambda item: self._policy_relevance_rank(item, anchors, topics), reverse=True)
        return kept, {
            "status": "NOT_REQUIRED" if not anchors and not topics else ("PASSED" if kept else "REJECTED"),
            "anchors": anchors,
            "topics": topics,
            "inputCount": len(evidence),
            "keptCount": len(kept),
        }

    def _policy_relevance_rank(
        self,
        item: dict[str, Any],
        anchors: list[str],
        topics: list[str],
    ) -> tuple[int, int, int]:
        heading = " ".join(
            str(item.get(field) or "") for field in ("title", "sectionTitle", "sectionPath", "tags")
        ).upper()
        heading_matches = sum(1 for term in (*anchors, *topics) if term.upper() in heading)
        topic_heading_matches = sum(1 for term in topics if term.upper() in heading)
        return topic_heading_matches, heading_matches, 0

    def _policy_query_anchors(self, keyword: str | None) -> list[str]:
        source = (keyword or "").upper()
        if not any(marker in source for marker in ("政策", "规则", "规定", "说明")):
            return []
        source = re.sub(r"\bAIM\d{8,}\b", "", source, flags=re.IGNORECASE)
        text = re.sub(r"[^A-Za-z0-9\u4e00-\u9fff]+", "", source)
        for term in sorted((*self._POLICY_DOMAIN_TERMS, *self._POLICY_QUERY_FILLERS), key=len, reverse=True):
            text = text.replace(term, "")
        return [part for part in re.findall(r"[A-Z0-9]+|[\u4e00-\u9fff]+", text) if len(part) >= 4]

    def _policy_topic_terms(self, keyword: str | None) -> list[str]:
        message = keyword or ""
        return [term for term in self._POLICY_TOPIC_TERMS if term in message]

    def _evidence_matches_terms(
        self,
        item: dict[str, Any],
        terms: list[str],
        *,
        allow_partial_anchor: bool = False,
    ) -> bool:
        searchable = " ".join(
            str(item.get(field) or "")
            for field in ("title", "content", "snippet", "sectionTitle", "sectionPath", "tags")
        ).upper()
        compact = re.sub(r"\s+", "", searchable)
        for term in terms:
            if term in compact:
                return True
            if allow_partial_anchor and re.fullmatch(r"[\u4e00-\u9fff]+", term):
                bigrams = {term[index:index + 2] for index in range(len(term) - 1)}
                matched = sum(1 for token in bigrams if token in compact)
                if bigrams and matched / len(bigrams) >= 0.5:
                    return True
        return False

    def _merge_relevance_validation(
        self,
        first: dict[str, Any],
        second: dict[str, Any],
    ) -> dict[str, Any]:
        anchors = list(dict.fromkeys([*first.get("anchors", []), *second.get("anchors", [])]))
        topics = list(dict.fromkeys([*first.get("topics", []), *second.get("topics", [])]))
        kept_count = int(first.get("keptCount", 0)) + int(second.get("keptCount", 0))
        input_count = int(first.get("inputCount", 0)) + int(second.get("inputCount", 0))
        return {
            "status": "PASSED" if kept_count else ("REJECTED" if anchors and input_count else "NO_EVIDENCE"),
            "anchors": anchors,
            "topics": topics,
            "inputCount": input_count,
            "keptCount": kept_count,
        }

    def _evidence_guardrail_metadata(
        self,
        blocked_docs: list[dict[str, object]],
        blocked_chunks: list[dict[str, object]],
    ) -> dict[str, Any]:
        blocked = [*blocked_docs, *blocked_chunks]
        return {
            "status": "FILTERED" if blocked else "CLEAR",
            "blockedEvidenceCount": len(blocked),
            "blockedEvidence": blocked[:20],
            "policyVersion": "GUARDRAILS_V1",
        }

    def _empty_evidence_status(self, blocked: list[dict[str, object]]) -> str:
        return "UNSAFE_EVIDENCE" if blocked else "NO_MATCH"

    def _empty_evidence_reason(self, blocked: list[dict[str, object]]) -> str:
        return "INDIRECT_PROMPT_INJECTION" if blocked else "NO_MATCH"

    def _rewrite_policy_keyword(self, keyword: str | None) -> str:
        message = (keyword or "").strip()
        if not message:
            return ""
        hits = [term for term in self._POLICY_DOMAIN_TERMS if term in message]
        if any(term in message for term in ("还没付款", "没付款", "未付款", "尚未付款")):
            hits.extend(["待支付", "未支付", "支付规则", "订单有效期"])
        if "多久" in message and "发货" in message:
            hits.extend(["48 小时", "常规发货"])
        if "会锁定" in message or "锁定吗" in message:
            hits.append("不锁定")
        if not hits:
            return message
        return " ".join(dict.fromkeys(hits))

    async def _search_policy_docs(
        self,
        keyword: str | None,
        top_k: int,
        source_type: str | None,
        auth_token: str | None,
        category_id: int | None,
    ) -> list[dict[str, Any]]:
        params: dict[str, Any] = {"keyword": keyword or "", "topK": top_k}
        if source_type:
            params["sourceType"] = source_type
        if category_id is not None:
            params["categoryId"] = category_id
        docs = await self._get_data("/internal/ai/knowledge", params, self._auth_headers(auth_token))
        if not isinstance(docs, list):
            return []
        return docs

    async def _search_policy_chunks(
        self,
        keyword: str | None,
        top_k: int,
        source_type: str | None,
        auth_token: str | None,
        category_id: int | None,
    ) -> list[dict[str, Any]]:
        params: dict[str, Any] = {"keyword": keyword or "", "topK": top_k, "retrievalSource": "chunk"}
        if source_type:
            params["sourceType"] = source_type
        if category_id is not None:
            params["categoryId"] = category_id
        chunks = await self._get_data("/internal/ai/knowledge", params, self._auth_headers(auth_token))
        if not isinstance(chunks, list):
            return []
        return chunks

    async def _search_policy_hybrid(
        self,
        keyword: str,
        top_k: int,
        source_type: str | None,
        auth_token: str | None,
        category_id: int | None,
    ) -> tuple[list[dict[str, Any]], dict[str, Any]]:
        recall_limit = 20
        keyword_hits = await self._search_policy_chunks(
            keyword, recall_limit, source_type, auth_token, category_id
        )
        vector_hits: list[dict[str, Any]] = []
        vector_error: str | None = None
        try:
            scope = await self._get_data(
                "/internal/ai/knowledge/scope",
                headers=self._auth_headers(auth_token),
            )
            vector = await embedding_provider.embed(keyword)
            raw_hits = await asyncio.to_thread(
                milvus_store.search,
                vector,
                recall_limit,
                self._milvus_scope_filter(scope),
            )
            ranked_ids = [int(item["chunk_id"]) for item in raw_hits if item.get("chunk_id") is not None]
            if ranked_ids:
                body: dict[str, Any] = {"chunkIds": ranked_ids}
                if category_id is not None:
                    body["categoryId"] = category_id
                if source_type:
                    body["sourceType"] = source_type
                hydrated = await self._post_data(
                    "/internal/ai/knowledge/chunks/retrieve",
                    body,
                    self._auth_headers(auth_token),
                )
                by_id = {
                    int(item["chunkId"]): item
                    for item in hydrated or []
                    if isinstance(item, dict) and item.get("chunkId") is not None
                }
                scores = {
                    int(item["chunk_id"]): item.get("score")
                    for item in raw_hits
                    if item.get("chunk_id") is not None
                }
                for chunk_id in ranked_ids:
                    item = by_id.get(chunk_id)
                    if item is not None:
                        item["vectorScore"] = scores.get(chunk_id)
                        vector_hits.append(item)
        except Exception as exc:
            vector_error = str(exc)

        fused = self._rrf_fuse(keyword_hits, vector_hits, max(1, min(top_k, 10)))
        return fused, {
            "keywordHitCount": len(keyword_hits),
            "vectorHitCount": len(vector_hits),
            "vectorError": vector_error,
            "fusion": "RRF",
        }

    def _rrf_fuse(
        self,
        keyword_hits: list[dict[str, Any]],
        vector_hits: list[dict[str, Any]],
        top_k: int,
    ) -> list[dict[str, Any]]:
        entries: dict[str, dict[str, Any]] = {}
        for source, hits in (("keyword", keyword_hits), ("vector", vector_hits)):
            for rank, item in enumerate(hits, start=1):
                key = str(item.get("contentHash") or f"chunk:{item.get('chunkId') or item.get('id')}")
                entry = entries.setdefault(
                    key,
                    {"item": dict(item), "score": 0.0, "sources": [], "sourceTrustScore": 0.0},
                )
                entry["score"] += 1.0 / (60 + rank)
                entry["sources"].append(source)
                try:
                    trust_score = float(item.get("sourceTrustScore", 0.5))
                except (TypeError, ValueError):
                    trust_score = 0.5
                entry["sourceTrustScore"] = max(
                    entry["sourceTrustScore"], max(0.0, min(1.0, trust_score))
                )
                if source == "vector" and not entry["item"].get("vectorScore"):
                    entry["item"]["vectorScore"] = item.get("vectorScore")
        ranked = sorted(
            entries.values(),
            key=lambda entry: (entry["score"], entry["sourceTrustScore"]),
            reverse=True,
        )
        result: list[dict[str, Any]] = []
        for entry in ranked[:top_k]:
            item = entry["item"]
            item["score"] = round(entry["score"], 8)
            item["sourceTrustScore"] = round(entry["sourceTrustScore"], 3)
            item["retrievalSource"] = "+".join(entry["sources"])
            result.append(item)
        return result

    def _milvus_scope_filter(self, scope: Any) -> str:
        role = str((scope or {}).get("role") or "PUBLIC_USER").upper()
        if role not in {"PUBLIC_USER", "MEMBER", "ADMIN"}:
            role = "PUBLIC_USER"
        tenant = str((scope or {}).get("tenantId") or "default").replace('"', '\\"')
        now_utc = datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
        visibility = ['visibility_scope == "PUBLIC_USER"', 'visibility_scope == "PUBLIC"']
        if role in ("MEMBER", "ADMIN"):
            visibility.extend(['visibility_scope == "AUTH_USER"', 'visibility_scope == "AUTHENTICATED"', 'visibility_scope == "MEMBER"'])
        if role == "ADMIN":
            visibility.extend(['visibility_scope == "ADMIN"', 'visibility_scope == "ADMIN_ONLY"', 'visibility_scope == "PRIVATE"'])
        role_filter = f'role_{role.lower().replace("_user", "")} == true'
        effective_filter = f'(effective_time == "" or effective_time <= "{now_utc}")'
        expire_filter = f'(expire_time == "" or expire_time > "{now_utc}")'
        return (
            'status == "ACTIVE" and is_deleted == false '
            f'and tenant_id == "{tenant}" and (' + " or ".join(visibility) + ") "
            f"and {role_filter} and {effective_filter} and {expire_filter}"
        )

    def _auth_headers(self, auth_token: str | None) -> dict[str, str] | None:
        return {"token": auth_token} if auth_token else None

    async def list_vector_sync_chunks(self, sync_status: str | None = "PENDING", limit: int = 100) -> list[dict[str, Any]]:
        params: dict[str, Any] = {"limit": limit}
        if sync_status:
            params["syncStatus"] = sync_status
        chunks = await self._get_data("/internal/ai/knowledge/chunks", params=params)
        if not isinstance(chunks, list):
            return []
        return chunks

    async def claim_vector_deletion_chunks(self, limit: int = 100) -> list[dict[str, Any]]:
        data = await self._post_data(
            "/internal/ai/knowledge/chunks/vector-deletions/claim",
            {"limit": limit},
        )
        return data if isinstance(data, list) else []

    async def complete_vector_deletion(self, chunk_id: int, claim_token: str) -> dict[str, Any]:
        data = await self._post_data(
            f"/internal/ai/knowledge/chunks/{chunk_id}/vector-deletion-complete",
            {"claimToken": claim_token},
        )
        return data if isinstance(data, dict) else {}

    async def fail_vector_deletion(
        self, chunk_id: int, claim_token: str, error_message: str
    ) -> dict[str, Any]:
        data = await self._post_data(
            f"/internal/ai/knowledge/chunks/{chunk_id}/vector-deletion-failed",
            {"claimToken": claim_token, "errorMessage": error_message[:1000]},
        )
        return data if isinstance(data, dict) else {}

    async def rebuild_knowledge_chunks(self) -> dict[str, Any]:
        data = await self._post_data("/internal/ai/knowledge/chunks/rebuild", {})
        return data if isinstance(data, dict) else {}

    async def list_knowledge_docs(
        self,
        keyword: str | None = None,
        source_type: str | None = None,
        limit: int = 100,
    ) -> list[dict[str, Any]]:
        params: dict[str, Any] = {"keyword": keyword or "", "topK": max(1, min(limit, 100))}
        if source_type:
            params["sourceType"] = source_type
        data = await self._get_data("/internal/ai/knowledge", params=params)
        return data if isinstance(data, list) else []

    async def update_chunk_embedding(
        self,
        chunk_id: int,
        *,
        embedding_id: str | None,
        embedding_model: str,
        embedding_model_version: str,
        embedding_sync_status: str,
        vector_collection: str,
        status: str | None = None,
    ) -> dict[str, Any]:
        body: dict[str, Any] = {
            "embeddingId": embedding_id,
            "embeddingModel": embedding_model,
            "embeddingModelVersion": embedding_model_version,
            "embeddingSyncStatus": embedding_sync_status,
            "vectorCollection": vector_collection,
        }
        if status:
            body["status"] = status
        data = await self._post_data(
            f"/internal/ai/knowledge/chunks/{chunk_id}/embedding",
            body,
        )
        return data if isinstance(data, dict) else {}

    async def get_knowledge_task(self, task_id: str) -> dict[str, Any]:
        data = await self._get_data(f"/internal/ai/knowledge/tasks/{task_id}")
        return data if isinstance(data, dict) else {}

    async def get_knowledge_task_acceptance(self, task_id: str) -> dict[str, Any]:
        data = await self._get_data(f"/internal/ai/knowledge/tasks/{task_id}/acceptance")
        return data if isinstance(data, dict) else {}

    async def record_knowledge_task_event(
        self,
        task_id: str,
        *,
        event_type: str,
        title: str,
        detail: str = "",
        progress_current: int | None = None,
        progress_total: int | None = None,
        ok: bool = True,
        error_code: str | None = None,
        suggestion: str | None = None,
    ) -> dict[str, Any]:
        body: dict[str, Any] = {
            "eventType": event_type,
            "title": title,
            "detail": detail,
            "ok": ok,
        }
        if progress_current is not None:
            body["progressCurrent"] = progress_current
        if progress_total is not None:
            body["progressTotal"] = progress_total
        if error_code:
            body["errorCode"] = error_code
        if suggestion:
            body["suggestion"] = suggestion
        data = await self._post_data(f"/internal/ai/knowledge/tasks/{task_id}/events", body)
        return data if isinstance(data, dict) else {}

    async def update_knowledge_task_status(
        self,
        task_id: str,
        *,
        status: str,
        current_step: str | None = None,
        progress_current: int | None = None,
        progress_total: int | None = None,
        error_code: str | None = None,
        error_message: str | None = None,
    ) -> dict[str, Any]:
        body: dict[str, Any] = {"status": status}
        if current_step is not None:
            body["currentStep"] = current_step
        if progress_current is not None:
            body["progressCurrent"] = progress_current
        if progress_total is not None:
            body["progressTotal"] = progress_total
        if error_code:
            body["errorCode"] = error_code
        if error_message:
            body["errorMessage"] = error_message
        data = await self._post_data(f"/internal/ai/knowledge/tasks/{task_id}/status", body)
        return data if isinstance(data, dict) else {}

    async def update_knowledge_doc_version_parse_result(
        self,
        version_id: int,
        *,
        parsed_json_path: str,
        preview_text_path: str,
        page_count: int,
        paragraph_count: int,
        table_count: int,
        image_count: int,
        pii_count: int | None = None,
        prompt_risk_level: str | None = None,
        status: str,
    ) -> dict[str, Any]:
        body: dict[str, Any] = {
            "parsedJsonPath": parsed_json_path,
            "previewTextPath": preview_text_path,
            "pageCount": page_count,
            "paragraphCount": paragraph_count,
            "tableCount": table_count,
            "imageCount": image_count,
            "status": status,
        }
        if pii_count is not None:
            body["piiCount"] = pii_count
        if prompt_risk_level is not None:
            body["promptRiskLevel"] = prompt_risk_level
        data = await self._post_data(f"/internal/ai/knowledge/tasks/versions/{version_id}/parse-result", body)
        return data if isinstance(data, dict) else {}

    async def replace_knowledge_task_chunks(
        self,
        task_id: str,
        *,
        chunks: list[dict[str, Any]],
        pii_count: int,
        prompt_risk_level: str,
        version_status: str,
        doc_status: str,
    ) -> dict[str, Any]:
        data = await self._post_data(
            f"/internal/ai/knowledge/tasks/{task_id}/chunks",
            {
                "chunks": chunks,
                "piiCount": pii_count,
                "promptRiskLevel": prompt_risk_level,
                "versionStatus": version_status,
                "docStatus": doc_status,
            },
        )
        return data if isinstance(data, dict) else {}

    async def list_knowledge_task_chunks(self, task_id: str) -> list[dict[str, Any]]:
        data = await self._get_data(f"/internal/ai/knowledge/tasks/{task_id}/chunks")
        return data if isinstance(data, list) else []

    async def get_embedding_cache(self, content_hash: str, embedding_model: str,
                                  retrieval_epoch: int = 0) -> dict[str, Any]:
        data = await self._get_data(
            "/internal/ai/knowledge/tasks/embedding-cache",
            params={"contentHash": content_hash, "embeddingModel": embedding_model,
                    "retrievalEpoch": retrieval_epoch},
        )
        return data if isinstance(data, dict) else {"hit": False}

    async def upsert_embedding_cache(
        self,
        *,
        content_hash: str,
        embedding_model: str,
        embedding_id: str,
        vector_dimension: int,
        retrieval_epoch: int = 0,
    ) -> dict[str, Any]:
        data = await self._post_data(
            "/internal/ai/knowledge/tasks/embedding-cache",
            {
                "contentHash": content_hash,
                "embeddingModel": embedding_model,
                "embeddingId": embedding_id,
                "vectorDimension": vector_dimension,
                "retrievalEpoch": retrieval_epoch,
            },
        )
        return data if isinstance(data, dict) else {}

    async def save_knowledge_evaluation(
        self,
        task_id: str,
        *,
        retrieval_tests: list[dict[str, Any]],
        quality_report: dict[str, Any],
        recommended_status: str,
    ) -> dict[str, Any]:
        data = await self._post_data(
            f"/internal/ai/knowledge/tasks/{task_id}/evaluation",
            {
                "retrievalTests": retrieval_tests,
                "qualityReport": quality_report,
                "recommendedStatus": recommended_status,
            },
        )
        return data if isinstance(data, dict) else {}

    def _chunk_shadow_result(self, mode: str, chunks: list[dict[str, Any]]) -> dict[str, Any] | None:
        if mode not in ("HYBRID", "VECTOR"):
            return None
        return {
            "source": "chunk",
            "available": bool(chunks),
            "retrievalStatus": "OK" if chunks else "NO_MATCH",
            "candidateCount": len(chunks),
        }

    async def get_order_detail(self, order_ref: int | str, token: str) -> dict[str, Any] | None:
        return await self._get_data(f"/internal/ai/orders/{order_ref}", headers={"token": token})

    async def list_my_orders(
        self,
        token: str,
        status: int | None = None,
        limit: int = 5,
    ) -> list[dict[str, Any]]:
        params: dict[str, Any] = {"limit": limit}
        if status is not None:
            params["status"] = status
        orders = await self._get_data("/internal/ai/orders", params=params, headers={"token": token})
        if not isinstance(orders, list):
            return []
        return orders

    async def list_my_coupons(self, token: str) -> list[dict[str, Any]]:
        coupons = await self._get_data("/internal/ai/coupons/my", headers={"token": token})
        if not isinstance(coupons, list):
            return []
        return coupons

    async def list_coupon_center(self, token: str) -> list[dict[str, Any]]:
        coupons = await self._get_data("/internal/ai/coupons/center", headers={"token": token})
        if not isinstance(coupons, list):
            return []
        return coupons

    async def list_my_returns(self, token: str) -> list[dict[str, Any]]:
        returns = await self._get_data("/internal/ai/returns", headers={"token": token})
        if not isinstance(returns, list):
            return []
        return returns

    async def get_return_detail(self, return_id: int, token: str) -> dict[str, Any] | None:
        return await self._get_data(f"/internal/ai/returns/{return_id}", headers={"token": token})

    async def list_my_addresses(self, token: str) -> list[dict[str, Any]]:
        addresses = await self._get_data("/internal/ai/addresses", headers={"token": token})
        if not isinstance(addresses, list):
            return []
        return addresses

    async def execute_confirmed_action(
        self,
        action_id: str,
        action_type: str,
        arguments: dict[str, Any],
        token: str,
    ) -> dict[str, Any]:
        paths = {
            "ADD_TO_CART": "/internal/ai/actions/cart/add",
            "CLAIM_COUPON": "/internal/ai/actions/coupons/claim",
            "CANCEL_ORDER": "/internal/ai/actions/orders/cancel",
            "APPLY_RETURN": "/internal/ai/actions/returns/apply",
        }
        path = paths.get(action_type)
        if path is None:
            raise ValueError("不支持的确认操作类型")
        body = dict(arguments)
        body["actionId"] = action_id
        try:
            data = await self._post_data(path, body, headers={"token": token})
        except (httpx.TimeoutException, httpx.TransportError) as exc:
            from app.actions.pending_store import RetryableActionError

            raise RetryableActionError("业务服务响应中断，可使用原确认操作安全重试") from exc
        return data if isinstance(data, dict) else {}


java_client = JavaClient()
