from __future__ import annotations

import hashlib
import json
import re
from dataclasses import dataclass, asdict
from typing import Any

from app.guardrails import guardrail_service


PHONE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
ID_CARD = re.compile(r"(?<!\d)\d{17}[0-9Xx](?!\d)")
BANK_CARD = re.compile(r"(?<!\d)(?:\d[ -]?){16,19}(?!\d)")
ORDER_SN = re.compile(r"\bAIM\d{14,24}\b", re.IGNORECASE)
CONTROL_CHARS = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f]")
SPACES = re.compile(r"[ \t]+")
BLANK_LINES = re.compile(r"\n{3,}")

TARGET_CHARS = 900
MAX_CHARS = 1400
MIN_CHARS = 120


@dataclass
class PipelineChunk:
    chunkNo: int
    chunkKey: str
    chunkType: str
    title: str
    sectionTitle: str | None
    sectionPath: str
    originalContent: str
    maskedContent: str
    indexContent: str
    snippet: str
    tokenCount: int
    contentHash: str
    chunkHash: str
    pageStart: int | None
    pageEnd: int | None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass
class PipelineResult:
    cleanText: str
    maskedText: str
    piiCount: int
    piiTypes: list[str]
    promptRiskLevel: str
    promptRiskHits: list[str]
    chunks: list[PipelineChunk]

    def to_dict(self) -> dict[str, Any]:
        return {
            "cleanText": self.cleanText,
            "maskedText": self.maskedText,
            "piiCount": self.piiCount,
            "piiTypes": self.piiTypes,
            "promptRiskLevel": self.promptRiskLevel,
            "promptRiskHits": self.promptRiskHits,
            "chunks": [chunk.to_dict() for chunk in self.chunks],
        }


def build_pipeline(parsed: dict[str, Any], *, doc_id: int, doc_version_id: int, title: str, source_type: str) -> PipelineResult:
    nodes = parsed.get("nodes") or []
    normalized_nodes = normalize_nodes(nodes)
    clean_text = "\n\n".join(node["text"] for node in normalized_nodes if node.get("text"))
    masked_text, pii_types, pii_count = mask_pii(clean_text)
    prompt_risk_level, prompt_risk_hits = detect_prompt_injection(clean_text)
    chunks = build_chunks(
        normalized_nodes,
        doc_id=doc_id,
        doc_version_id=doc_version_id,
        title=title,
        source_type=source_type,
    )
    return PipelineResult(
        cleanText=clean_text,
        maskedText=masked_text,
        piiCount=pii_count,
        piiTypes=pii_types,
        promptRiskLevel=prompt_risk_level,
        promptRiskHits=prompt_risk_hits,
        chunks=chunks,
    )


def normalize_nodes(nodes: list[dict[str, Any]]) -> list[dict[str, Any]]:
    normalized: list[dict[str, Any]] = []
    for node in nodes:
        text = clean_text(str(node.get("text") or ""))
        if not text:
            continue
        item = dict(node)
        item["text"] = text
        item["section_path"] = normalize_section_path(node.get("section_path"))
        normalized.append(item)
    return normalized


def clean_text(text: str) -> str:
    text = text.replace("\ufeff", "").replace("\r\n", "\n").replace("\r", "\n")
    text = CONTROL_CHARS.sub("", text)
    lines = [SPACES.sub(" ", line).strip() for line in text.split("\n")]
    text = "\n".join(line for line in lines if line)
    return BLANK_LINES.sub("\n\n", text).strip()


def mask_pii(text: str) -> tuple[str, list[str], int]:
    pii_types: set[str] = set()
    count = 0

    def replace_phone(match: re.Match[str]) -> str:
        nonlocal count
        pii_types.add("PHONE")
        count += 1
        value = match.group(0)
        return value[:3] + "****" + value[-4:]

    def replace_full(mask_type: str, mask_text: str):
        def replacer(match: re.Match[str]) -> str:
            nonlocal count
            pii_types.add(mask_type)
            count += 1
            return mask_text

        return replacer

    masked = PHONE.sub(replace_phone, text)
    masked = ID_CARD.sub(replace_full("ID_CARD", "***ID_CARD***"), masked)
    masked = BANK_CARD.sub(replace_full("BANK_CARD", "***BANK_CARD***"), masked)
    masked = ORDER_SN.sub(replace_full("ORDER_SN", "***ORDER_SN***"), masked)
    return masked, sorted(pii_types), count


def detect_prompt_injection(text: str) -> tuple[str, list[str]]:
    decision = guardrail_service.evaluate_evidence({"content": text})
    hits = [finding.ruleId for finding in decision.findings]
    if decision.allowed:
        return "LOW", []
    return "HIGH" if decision.riskLevel.value in {"HIGH", "CRITICAL"} else "MEDIUM", hits


def build_chunks(
    nodes: list[dict[str, Any]],
    *,
    doc_id: int,
    doc_version_id: int,
    title: str,
    source_type: str,
) -> list[PipelineChunk]:
    chunks: list[PipelineChunk] = []
    buffer: list[dict[str, Any]] = []
    buffer_len = 0

    def flush() -> None:
        nonlocal buffer, buffer_len
        if not buffer:
            return
        merged = "\n".join(item["text"] for item in buffer).strip()
        if not merged:
            buffer = []
            buffer_len = 0
            return
        section_path = first_non_empty_section_path(buffer)
        masked, _, _ = mask_pii(merged)
        index_content = build_index_content(title, section_path, masked)
        content_hash = sha256(index_content)
        chunk_hash = sha256(json.dumps({"path": section_path, "content": merged}, ensure_ascii=False))
        chunk_no = len(chunks) + 1
        chunks.append(
            PipelineChunk(
                chunkNo=chunk_no,
                chunkKey=f"{doc_id}:{doc_version_id}:{chunk_no}:{content_hash[:16]}",
                chunkType=chunk_type(buffer),
                title=title,
                sectionTitle=section_path[-1] if section_path else None,
                sectionPath=json.dumps(section_path, ensure_ascii=False),
                originalContent=merged,
                maskedContent=masked,
                indexContent=index_content,
                snippet=masked[:300],
                tokenCount=estimate_tokens(index_content),
                contentHash=content_hash,
                chunkHash=chunk_hash,
                pageStart=min_page(buffer),
                pageEnd=max_page(buffer),
            )
        )
        buffer = []
        buffer_len = 0

    for node in nodes:
        node_text = node["text"]
        if node.get("type") == "table":
            flush()
            buffer = [node]
            buffer_len = len(node_text)
            flush()
            continue
        if node.get("type") == "heading":
            if buffer_len >= MIN_CHARS:
                flush()
            buffer.append(node)
            buffer_len += len(node_text)
            continue
        if buffer_len + len(node_text) > MAX_CHARS and buffer_len >= MIN_CHARS:
            flush()
        buffer.append(node)
        buffer_len += len(node_text)
        if buffer_len >= TARGET_CHARS:
            flush()
    flush()
    return chunks


def build_index_content(title: str, section_path: list[str], masked: str) -> str:
    parts = [title.strip()]
    if section_path:
        parts.append(" > ".join(section_path))
    parts.append(masked)
    return "\n".join(part for part in parts if part)


def normalize_section_path(value: Any) -> list[str]:
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    return []


def first_non_empty_section_path(items: list[dict[str, Any]]) -> list[str]:
    for item in items:
        path = normalize_section_path(item.get("section_path"))
        if path:
            return path
    return []


def chunk_type(items: list[dict[str, Any]]) -> str:
    return "TABLE" if any(item.get("type") == "table" for item in items) else "TEXT"


def min_page(items: list[dict[str, Any]]) -> int | None:
    pages = [int(item["page"]) for item in items if item.get("page")]
    return min(pages) if pages else None


def max_page(items: list[dict[str, Any]]) -> int | None:
    pages = [int(item["page"]) for item in items if item.get("page")]
    return max(pages) if pages else None


def sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def estimate_tokens(text: str) -> int:
    return max(1, len(text) // 2)
