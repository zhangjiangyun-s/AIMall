import asyncio
import sys
import types


httpx_stub = types.ModuleType("httpx")


class AsyncClientStub:
    def __init__(self, *args, **kwargs):
        self.is_closed = False

    async def aclose(self):
        self.is_closed = True


httpx_stub.AsyncClient = AsyncClientStub
sys.modules.setdefault("httpx", httpx_stub)

fastapi_stub = types.ModuleType("fastapi")
responses_stub = types.ModuleType("fastapi.responses")


class APIRouterStub:
    def __init__(self, *args, **kwargs):
        pass

    def post(self, *args, **kwargs):
        def decorator(func):
            return func

        return decorator


class StreamingResponseStub:
    def __init__(self, *args, **kwargs):
        pass


fastapi_stub.APIRouter = APIRouterStub


class HTTPExceptionStub(Exception):
    def __init__(self, status_code: int, detail: str):
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail


fastapi_stub.HTTPException = HTTPExceptionStub
responses_stub.StreamingResponse = StreamingResponseStub
sys.modules.setdefault("fastapi", fastapi_stub)
sys.modules.setdefault("fastapi.responses", responses_stub)

from app.api.chat_api import summarize_tool_result
from app.config.settings import normalize_rag_retrieval_mode, settings
from app.schemas.tool_schema import ToolCallRecord
from app.tools.java_client import JavaClient


class FakeJavaClient(JavaClient):
    async def _search_policy_docs(
        self,
        keyword,
        top_k,
        source_type,
        auth_token=None,
        category_id=None,
    ):
        return [
            {
                "title": "退货规则",
                "source": "POLICY#1",
                "snippet": "用户签收后 7 天内可申请无理由退货。",
            }
        ]

    async def _search_policy_hybrid(
        self,
        keyword,
        top_k,
        source_type,
        auth_token=None,
        category_id=None,
    ):
        return [], {"keywordCount": 0, "vectorCount": 0, "vectorError": None}


def run(coro):
    return asyncio.run(coro)


def test_rag_mode_normalization():
    assert normalize_rag_retrieval_mode(None) == "DOC_ONLY"
    assert normalize_rag_retrieval_mode("hybrid_shadow") == "HYBRID"
    assert normalize_rag_retrieval_mode("chunk_only") == "VECTOR"
    assert normalize_rag_retrieval_mode("bad-value") == "DOC_ONLY"


def test_policy_query_rewrite_keeps_decisive_business_terms():
    client = FakeJavaClient()

    assert "自动关闭" in client._rewrite_policy_keyword("待支付订单多久自动关闭")
    assert "支付规则" in client._rewrite_policy_keyword("订单还没付款，多久自动关闭")
    assert "恢复" in client._rewrite_policy_keyword("已关闭订单还能恢复吗")
    assert "到账" in client._rewrite_policy_keyword("售后审核通过后退款多久到账")


def test_policy_relevance_rejects_unmatched_specific_phrase():
    client = FakeJavaClient()
    evidence, metadata = client._filter_policy_relevance(
        "AIMall 火星量子配送政策是什么",
        [{"title": "配送与发货规则", "snippet": "普通商品支付后 48 小时内发货。"}],
    )

    assert evidence == []
    assert metadata["status"] == "REJECTED"
    assert metadata["anchors"] == ["火星量子"]


def test_policy_relevance_keeps_evidence_matching_specific_phrase():
    client = FakeJavaClient()
    evidence, metadata = client._filter_policy_relevance(
        "偏远地区配送政策",
        [{"title": "配送与发货规则", "snippet": "偏远地区的配送时间可能顺延。"}],
    )

    assert len(evidence) == 1
    assert metadata["status"] == "PASSED"


def test_policy_relevance_ignores_order_id_in_mixed_order_policy_query():
    client = FakeJavaClient()
    evidence, metadata = client._filter_policy_relevance(
        "订单号 AIM20260714000000000002 已经付款了，商家什么时候发货？",
        [{"title": "配送与发货规则", "snippet": "普通现货商品支付后 48 小时内发货。"}],
    )

    assert len(evidence) == 1
    assert metadata["anchors"] == []
    assert metadata["topics"] == ["发货"]


def test_policy_relevance_filters_cross_domain_chunks_by_topic():
    client = FakeJavaClient()
    evidence, metadata = client._filter_policy_relevance(
        "请说明 AIMall 的购物车规则",
        [
            {"title": "购物车规则", "snippet": "购物车不锁定价格和库存。"},
            {"title": "售后服务规则", "snippet": "退货申请需要审核。"},
        ],
    )

    assert [item["title"] for item in evidence] == ["购物车规则"]
    assert metadata["topics"] == ["购物车"]


def test_policy_relevance_ignores_citation_request_wrappers():
    client = FakeJavaClient()
    evidence, metadata = client._filter_policy_relevance(
        "请说明 AIMall 的购物车规则，并给出引用依据。",
        [
            {"title": "购物车规则", "snippet": "购物车不锁定价格和库存。"},
            {"title": "售后服务规则", "snippet": "退货申请需要审核。"},
        ],
    )

    assert [item["title"] for item in evidence] == ["购物车规则"]
    assert metadata["anchors"] == []
    assert metadata["topics"] == ["购物车"]


def test_policy_relevance_ranks_heading_topic_before_incidental_body_match():
    client = FakeJavaClient()
    evidence, metadata = client._filter_policy_relevance(
        "订单已付款，商家什么时候发货？",
        [
            {"title": "售后服务规则", "snippet": "售后状态包括换货发货中。"},
            {"title": "配送与发货规则", "snippet": "普通现货通常在 48 小时内发货。"},
        ],
    )

    assert [item["title"] for item in evidence] == ["配送与发货规则", "售后服务规则"]
    assert metadata["topics"] == ["发货"]


def test_policy_search_returns_no_match_after_relevance_rejection():
    settings.RAG_RETRIEVAL_MODE = "DOC_ONLY"
    result = run(FakeJavaClient().search_policy_kb("AIMall 火星量子配送政策是什么", 5, None))

    assert result["evidence"] == []
    assert result["retrievalStatus"] == "NO_MATCH"
    assert result["refusalReason"] == "NO_MATCH"
    assert result["relevanceValidation"]["status"] == "REJECTED"


def test_doc_only_mode_keeps_current_doc_retrieval():
    settings.RAG_RETRIEVAL_MODE = "DOC_ONLY"
    result = run(FakeJavaClient().search_policy_kb("退货", 5, None))

    assert result["retrievalMode"] == "DOC_ONLY"
    assert result["primarySource"] == "doc"
    assert result["fallbackUsed"] is False
    assert result["documents"][0]["title"] == "退货规则"
    assert result["shadow"] is None


def test_hybrid_uses_doc_fallback_until_chunks_exist():
    settings.RAG_RETRIEVAL_MODE = "HYBRID"
    result = run(FakeJavaClient().search_policy_kb("退货", 5, None))

    assert result["primarySource"] == "doc"
    assert result["fallbackUsed"] is True
    assert result["retrievalStatus"] == "DOC_FALLBACK"


def test_vector_is_safe_before_chunk_index_exists():
    settings.RAG_RETRIEVAL_MODE = "VECTOR"
    result = run(FakeJavaClient().search_policy_kb("退货", 5, None))

    assert result["retrievalMode"] == "VECTOR"
    assert result["documents"] == []
    assert result["refusalReason"] == "NO_MATCH"


def test_policy_summary_supports_structured_rag_result():
    record = ToolCallRecord(
        name="search_policy_kb",
        arguments={"keyword": "退货"},
        ok=True,
        result={
            "retrievalMode": "HYBRID_SHADOW",
            "documents": [{"title": "退货规则", "source": "POLICY#1"}],
        },
        traceId="t1",
    )

    summary = summarize_tool_result(record)

    assert "检索到 1 条可引用政策资料" in summary
    assert "HYBRID_SHADOW" in summary
    assert "退货规则" in summary


if __name__ == "__main__":
    tests = [
        test_rag_mode_normalization,
        test_doc_only_mode_keeps_current_doc_retrieval,
        test_hybrid_shadow_keeps_doc_primary_and_records_shadow,
        test_chunk_with_doc_fallback_uses_doc_until_chunks_exist,
        test_chunk_only_is_safe_before_chunk_index_exists,
        test_policy_summary_supports_structured_rag_result,
    ]
    for test in tests:
        test()
    print("phase10.0.5 rag retrieval mode checks passed")
