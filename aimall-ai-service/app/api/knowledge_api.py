import json
from pathlib import Path

from fastapi import APIRouter, Query
from pydantic import BaseModel
from typing import Optional

from app.rag.document_pipeline import build_pipeline
from app.rag.document_parser import parse_document
from app.rag.evaluation import evaluate_document
from app.rag.milvus_store import milvus_store
from app.rag.vector_sync import (
    _compensate_lost_execution,
    _require_current_execution,
    check_task_consistency,
    sync_task_vectors,
)
from app.tools.java_client import java_client
router = APIRouter(prefix="/ai", tags=["Knowledge"])


class KnowledgeTaskProcessRequest(BaseModel):
    executionToken: str
    attemptNo: int


@router.post("/knowledge/rebuild")
async def rebuild_knowledge():
    result = await java_client.rebuild_knowledge_chunks()
    return {"code": 0, "message": "success", "data": result}


@router.get("/knowledge/docs")
async def list_knowledge_docs(
    keyword: Optional[str] = Query(None, description="Filter by title keyword"),
    source_type: Optional[str] = Query(None, description="Filter by source type"),
):
    docs = await java_client.list_knowledge_docs(keyword=keyword, source_type=source_type)
    return {"code": 0, "message": "success", "data": docs}


@router.post("/knowledge/tasks/{task_id}/process")
async def process_knowledge_task(task_id: str, request: KnowledgeTaskProcessRequest):
    """
    Process an uploaded knowledge document task.
    First-stage implementation parses PDF/DOCX/MD/TXT and writes observable task events.
    Chunk persistence and Milvus sync are implemented in the following steps.
    """
    execution_context = java_client.bind_knowledge_execution(task_id, request.executionToken)
    try:
        await java_client.update_knowledge_task_status(
            task_id,
            status="RUNNING",
            current_step="parse_started",
            progress_current=4,
            progress_total=10,
        )
        task = await java_client.get_knowledge_task(task_id)
        storage_path = str(task.get("storagePath") or "")
        file_type = str(task.get("fileType") or "")
        await java_client.record_knowledge_task_event(
            task_id,
            event_type="parse_started",
            title="开始解析文档",
            detail=f"{task.get('fileName', '')} / {file_type}",
            progress_current=4,
            progress_total=10,
        )
        parsed = parse_document(storage_path, file_type)
        parsed_json_path, preview_text_path = write_parse_artifacts(storage_path, parsed.to_dict())
        version_id = int(task.get("docVersionId") or 0)
        if version_id > 0:
            await java_client.update_knowledge_doc_version_parse_result(
                version_id,
                parsed_json_path=parsed_json_path,
                preview_text_path=preview_text_path,
                page_count=parsed.page_count,
                paragraph_count=parsed.paragraph_count,
                table_count=parsed.table_count,
                image_count=parsed.image_count,
                status="PROCESSING",
            )
        await java_client.record_knowledge_task_event(
            task_id,
            event_type="parse_completed",
            title="解析完成",
            detail=f"提取 {parsed.paragraph_count} 个段落、{parsed.table_count} 个表格、{parsed.page_count} 页，已保存 parsed.json 和 preview.txt",
            progress_current=5,
            progress_total=10,
        )
        await java_client.record_knowledge_task_event(
            task_id,
            event_type="clean_started",
            title="开始文本清洗",
            detail="清理空段落、控制字符和 PDF 断行，保留标题层级",
            progress_current=6,
            progress_total=10,
        )
        pipeline = build_pipeline(
            parsed.to_dict(),
            doc_id=int(task.get("docId") or 0),
            doc_version_id=int(task.get("docVersionId") or 0),
            title=str(task.get("title") or ""),
            source_type=str(task.get("sourceType") or "POLICY"),
        )
        await java_client.record_knowledge_task_event(
            task_id,
            event_type="clean_completed",
            title="文本清洗完成",
            detail=f"生成清洗文本 {len(pipeline.cleanText)} 字",
            progress_current=7,
            progress_total=10,
        )
        await java_client.record_knowledge_task_event(
            task_id,
            event_type="pii_checked",
            title="敏感信息检测完成",
            detail=f"发现 {pipeline.piiCount} 处敏感信息：{','.join(pipeline.piiTypes) if pipeline.piiTypes else '无'}",
            progress_current=7,
            progress_total=10,
        )
        await java_client.record_knowledge_task_event(
            task_id,
            event_type="prompt_injection_checked",
            title="Prompt 注入检测完成",
            detail=f"风险等级：{pipeline.promptRiskLevel}",
            progress_current=8,
            progress_total=10,
        )
        await java_client.record_knowledge_task_event(
            task_id,
            event_type="chunk_started",
            title="开始结构化分块",
            detail="按章节路径和表格结构生成可检索 chunk",
            progress_current=8,
            progress_total=10,
        )
        version_status = "REVIEW_REQUIRED" if pipeline.promptRiskLevel in ("MEDIUM", "HIGH") else "READY"
        doc_status = "REVIEW_REQUIRED" if pipeline.promptRiskLevel in ("MEDIUM", "HIGH") else "READY_TO_PUBLISH"
        chunk_result = await java_client.replace_knowledge_task_chunks(
            task_id,
            chunks=[chunk.to_dict() for chunk in pipeline.chunks],
            pii_count=pipeline.piiCount,
            prompt_risk_level=pipeline.promptRiskLevel,
            version_status=version_status,
            doc_status=doc_status,
        )
        await java_client.record_knowledge_task_event(
            task_id,
            event_type="chunk_completed",
            title="分块入库完成",
            detail=f"写入 {chunk_result.get('chunkCount', len(pipeline.chunks))} 个 chunk",
            progress_current=9,
            progress_total=10,
        )
        if version_id > 0:
            await java_client.update_knowledge_doc_version_parse_result(
                version_id,
                parsed_json_path=parsed_json_path,
                preview_text_path=preview_text_path,
                page_count=parsed.page_count,
                paragraph_count=parsed.paragraph_count,
                table_count=parsed.table_count,
                image_count=parsed.image_count,
                pii_count=pipeline.piiCount,
                prompt_risk_level=pipeline.promptRiskLevel,
                status=version_status,
            )
        if pipeline.promptRiskLevel in ("MEDIUM", "HIGH"):
            await java_client.record_knowledge_task_event(
                task_id,
                event_type="ready_for_review",
                title="等待人工审核",
                detail=f"Prompt 注入风险等级为 {pipeline.promptRiskLevel}，已阻止向量入库",
                progress_current=10,
                progress_total=10,
                ok=False,
                error_code="PROMPT_RISK_REVIEW_REQUIRED",
                suggestion="管理员审核原文后再决定是否发布",
            )
            await java_client.update_knowledge_task_status(
                task_id,
                status="SUCCESS",
                current_step="review_required",
                progress_current=10,
                progress_total=10,
            )
            vector_result = {"total": 0, "synced": 0, "failed": 0, "blocked": len(pipeline.chunks)}
            consistency = {"checked": 0, "missing": 0, "missingChunkIds": []}
            final_status = "REVIEW_REQUIRED"
        else:
            vector_result = await sync_task_vectors(task_id)
            await java_client.record_knowledge_task_event(
                task_id,
                event_type="vector_sync_completed",
                title="Milvus 写入完成",
                detail=f"成功 {vector_result['synced']}，失败 {vector_result['failed']}",
                progress_current=10,
                progress_total=10,
                ok=vector_result["failed"] == 0,
            )
            consistency = await check_task_consistency(task_id)
            consistency_ok = consistency["missing"] == 0 and vector_result["failed"] == 0
            await java_client.record_knowledge_task_event(
                task_id,
                event_type="consistency_checked",
                title="一致性校验完成",
                detail=f"检查 {consistency['checked']} 个 chunk，缺失 {consistency['missing']} 个向量",
                progress_current=10,
                progress_total=10,
                ok=consistency_ok,
                error_code=None if consistency_ok else "VECTOR_CONSISTENCY_FAILED",
                suggestion=None if consistency_ok else "重试失败 chunk 后再次执行一致性校验",
            )
            if consistency_ok:
                task_chunks = await java_client.list_knowledge_task_chunks(task_id)
                evaluation = await evaluate_document(
                    doc_id=int(task.get("docId") or 0),
                    title=str(task.get("title") or ""),
                    parsed=parsed.to_dict(),
                    chunks=task_chunks,
                    pii_count=pipeline.piiCount,
                    prompt_risk_level=pipeline.promptRiskLevel,
                    consistency=consistency,
                )
                if evaluation["recommendedStatus"] != "READY_TO_PUBLISH":
                    execution = await _require_current_execution(task_id)
                    execution_token = str(execution.get("executionToken") or "")
                    if not execution_token:
                        raise RuntimeError("Knowledge task response omitted execution token")
                    doc_id = int(task.get("docId") or 0)
                    version_id = int(task.get("docVersionId") or 0)
                    milvus_store.update_doc_version_status_for_execution(
                        doc_id, version_id, execution_token, "REVIEW_REQUIRED", False
                    )
                    try:
                        await _require_current_execution(task_id)
                    except Exception:
                        embedding_ids = milvus_store.get_doc_version_embeddings_for_execution(
                            doc_id, version_id, execution_token
                        )
                        await _compensate_lost_execution(embedding_ids, execution_token)
                        raise
                evaluation_result = await java_client.save_knowledge_evaluation(
                    task_id,
                    retrieval_tests=evaluation["retrievalTests"],
                    quality_report=evaluation["qualityReport"],
                    recommended_status=evaluation["recommendedStatus"],
                )
                await java_client.record_knowledge_task_event(
                    task_id,
                    event_type="retrieval_test_completed",
                    title="检索自测完成",
                    detail=f"通过 {evaluation_result.get('passedCount', 0)} / {evaluation_result.get('testCount', 0)}",
                    progress_current=10,
                    progress_total=10,
                    ok=evaluation_result.get("passedCount", 0) == evaluation_result.get("testCount", 0),
                )
                await java_client.record_knowledge_task_event(
                    task_id,
                    event_type="quality_report_generated",
                    title="质量评分完成",
                    detail=f"总分 {evaluation['qualityReport']['totalScore']}，等级 {evaluation['qualityReport']['grade']}",
                    progress_current=10,
                    progress_total=10,
                    ok=evaluation["qualityReport"]["grade"] in ("A", "B"),
                    error_code=None if evaluation["qualityReport"]["grade"] in ("A", "B") else "QUALITY_REVIEW_REQUIRED",
                    suggestion=None if evaluation["qualityReport"]["grade"] in ("A", "B") else "请管理员检查低分项和检索自测结果",
                )
                if evaluation["recommendedStatus"] != "READY_TO_PUBLISH":
                    await java_client.update_knowledge_task_status(
                        task_id,
                        status="SUCCESS",
                        current_step="review_required",
                        progress_current=10,
                        progress_total=10,
                    )
                    final_status = "REVIEW_REQUIRED"
                    return {
                        "code": 0,
                        "message": "success",
                        "data": {
                            "taskId": task_id,
                            "parsed": parsed.to_dict(),
                            "parsedJsonPath": parsed_json_path,
                            "previewTextPath": preview_text_path,
                            "chunkCount": len(pipeline.chunks),
                            "piiCount": pipeline.piiCount,
                            "promptRiskLevel": pipeline.promptRiskLevel,
                            "vectorResult": vector_result,
                            "consistency": consistency,
                            "evaluation": evaluation,
                            "status": final_status,
                        },
                    }
                await java_client.record_knowledge_task_event(
                    task_id,
                    event_type="ready_to_publish",
                    title="等待发布",
                    detail="文档解析、分块、向量写入和一致性校验均已完成",
                    progress_current=10,
                    progress_total=10,
                )
                await java_client.update_knowledge_task_status(
                    task_id,
                    status="SUCCESS",
                    current_step="ready_to_publish",
                    progress_current=10,
                    progress_total=10,
                )
                final_status = "READY_TO_PUBLISH"
            else:
                await java_client.update_knowledge_task_status(
                    task_id,
                    status="PARTIAL_FAILED",
                    current_step="consistency_checked",
                    progress_current=10,
                    progress_total=10,
                    error_code="VECTOR_CONSISTENCY_FAILED",
                    error_message=f"{consistency['missing']} 个 chunk 缺少向量",
                )
                final_status = "PARTIAL_FAILED"
        return {
            "code": 0,
            "message": "success",
            "data": {
                "taskId": task_id,
                "parsed": parsed.to_dict(),
                "parsedJsonPath": parsed_json_path,
                "previewTextPath": preview_text_path,
                "chunkCount": len(pipeline.chunks),
                "piiCount": pipeline.piiCount,
                "promptRiskLevel": pipeline.promptRiskLevel,
                "vectorResult": vector_result,
                "consistency": consistency,
                "status": final_status,
            },
        }
    except Exception as exc:
        await java_client.record_knowledge_task_event(
            task_id,
            event_type="failed",
            title="文档处理失败",
            detail=str(exc),
            progress_current=0,
            progress_total=10,
            ok=False,
            error_code="PROCESS_FAILED",
            suggestion="检查文件是否损坏、是否为加密 PDF，或确认 PDF/DOCX 解析依赖是否安装",
        )
        await java_client.update_knowledge_task_status(
            task_id,
            status="FAILED",
            current_step="failed",
            error_code="PROCESS_FAILED",
            error_message=str(exc),
        )
        return {"code": 1, "message": str(exc), "data": None}
    finally:
        java_client.reset_knowledge_execution(execution_context)


def write_parse_artifacts(storage_path: str, parsed: dict) -> tuple[str, str]:
    source = Path(storage_path)
    version_dir = source.parent
    version_no = version_dir.name
    doc_id = version_dir.parent.name
    knowledge_root = version_dir.parent.parent.parent
    parsed_dir = knowledge_root / "parsed" / doc_id / version_no
    preview_dir = knowledge_root / "preview" / doc_id / version_no
    parsed_dir.mkdir(parents=True, exist_ok=True)
    preview_dir.mkdir(parents=True, exist_ok=True)

    parsed_json = parsed_dir / "parsed.json"
    preview_text = preview_dir / "preview.txt"
    parsed_json.write_text(json.dumps(parsed, ensure_ascii=False, indent=2), encoding="utf-8")
    preview_text.write_text(build_preview_text(parsed), encoding="utf-8")
    return str(parsed_json), str(preview_text)


def build_preview_text(parsed: dict) -> str:
    lines: list[str] = []
    for node in parsed.get("nodes", []):
        text = str(node.get("text") or "").strip()
        if not text:
            continue
        if node.get("type") == "heading":
            lines.append(f"# {text}")
        else:
            lines.append(text)
        if len("\n".join(lines)) > 6000:
            break
    return "\n\n".join(lines)
