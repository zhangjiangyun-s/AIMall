from __future__ import annotations

import json
from typing import Any

from app.rag.embedding import embedding_provider
from app.rag.milvus_store import milvus_store


async def evaluate_document(
    *,
    doc_id: int,
    title: str,
    parsed: dict[str, Any],
    chunks: list[dict[str, Any]],
    pii_count: int,
    prompt_risk_level: str,
    consistency: dict[str, Any],
) -> dict[str, Any]:
    queries = build_test_queries(title, chunks)
    vectors = await embedding_provider.embed_batch(queries, concurrency=3, max_retries=3)
    tests: list[dict[str, Any]] = []
    for query, vector_or_error in zip(queries, vectors):
        if isinstance(vector_or_error, Exception):
            tests.append(
                {
                    "testQuery": query,
                    "hitDocId": None,
                    "hitChunkId": None,
                    "topScore": 0,
                    "passed": False,
                    "detail": f"Embedding 失败：{str(vector_or_error)[:200]}",
                }
            )
            continue
        hits = milvus_store.search(vector_or_error, limit=3)
        normalized_hits = [normalize_hit(item) for item in hits]
        matched = next((item for item in normalized_hits if item.get("docId") == doc_id), None)
        top = normalized_hits[0] if normalized_hits else {}
        tests.append(
            {
                "testQuery": query,
                "hitDocId": top.get("docId"),
                "hitChunkId": top.get("chunkId"),
                "topScore": round(float(top.get("score") or 0), 4),
                "passed": matched is not None,
                "detail": f"Top3 命中文档：{[item.get('docId') for item in normalized_hits]}",
            }
        )

    quality = build_quality_report(
        parsed=parsed,
        chunks=chunks,
        pii_count=pii_count,
        prompt_risk_level=prompt_risk_level,
        tests=tests,
        consistency=consistency,
    )
    return {
        "retrievalTests": tests,
        "qualityReport": quality,
        "recommendedStatus": "READY_TO_PUBLISH" if quality["grade"] in ("A", "B") else "REVIEW_REQUIRED",
    }


def build_test_queries(title: str, chunks: list[dict[str, Any]]) -> list[str]:
    candidates = [title.strip()]
    candidates.extend(str(chunk.get("sectionTitle") or "").strip() for chunk in chunks)
    candidates.extend(
        str(chunk.get("maskedContent") or "")[:80].strip()
        for chunk in chunks
        if chunk.get("chunkType") == "TABLE"
    )
    result: list[str] = []
    for item in candidates:
        if item and item not in result:
            result.append(item)
        if len(result) >= 4:
            break
    return result or ["商城规则"]


def normalize_hit(item: dict[str, Any]) -> dict[str, Any]:
    entity = item.get("entity") if isinstance(item.get("entity"), dict) else item
    return {
        "docId": int(entity.get("doc_id")) if entity.get("doc_id") is not None else None,
        "chunkId": int(entity.get("chunk_id")) if entity.get("chunk_id") is not None else None,
        "score": item.get("distance", item.get("score", 0)),
    }


def build_quality_report(
    *,
    parsed: dict[str, Any],
    chunks: list[dict[str, Any]],
    pii_count: int,
    prompt_risk_level: str,
    tests: list[dict[str, Any]],
    consistency: dict[str, Any],
) -> dict[str, Any]:
    paragraph_count = int(parsed.get("paragraphCount") or 0)
    parse_score = 100.0 if paragraph_count > 0 else 0.0
    reasonable_chunks = [
        chunk for chunk in chunks if 40 <= int(chunk.get("tokenCount") or 0) <= 900
    ]
    chunk_score = 0.0 if not chunks else round(len(reasonable_chunks) / len(chunks) * 100, 2)
    pii_score = max(0.0, 100.0 - min(60.0, pii_count * 5.0))
    prompt_risk_score = {"LOW": 100.0, "MEDIUM": 50.0, "HIGH": 0.0}.get(prompt_risk_level, 50.0)
    passed = len([test for test in tests if test.get("passed")])
    retrieval_score = 0.0 if not tests else round(passed / len(tests) * 100, 2)
    checked = int(consistency.get("checked") or 0)
    missing = int(consistency.get("missing") or 0)
    sync_score = 100.0 if checked > 0 and missing == 0 else max(0.0, 100.0 - missing * 20.0)
    total = round(
        parse_score * 0.2
        + chunk_score * 0.2
        + pii_score * 0.1
        + prompt_risk_score * 0.2
        + retrieval_score * 0.2
        + sync_score * 0.1,
        2,
    )
    grade = "A" if total >= 90 else "B" if total >= 80 else "C" if total >= 60 else "D"
    detail = json.dumps(
        {
            "paragraphCount": paragraph_count,
            "chunkCount": len(chunks),
            "reasonableChunkCount": len(reasonable_chunks),
            "piiCount": pii_count,
            "promptRiskLevel": prompt_risk_level,
            "retrievalPassed": passed,
            "retrievalTotal": len(tests),
            "consistencyMissing": missing,
        },
        ensure_ascii=False,
    )
    return {
        "parseScore": parse_score,
        "chunkScore": chunk_score,
        "piiScore": pii_score,
        "promptRiskScore": prompt_risk_score,
        "retrievalScore": retrieval_score,
        "syncScore": sync_score,
        "totalScore": total,
        "grade": grade,
        "detail": detail,
    }
