from __future__ import annotations

import json
import re
from decimal import Decimal, InvalidOperation
from typing import Any

from app.reflection.models import (
    ReflectionDecision,
    ReflectionFinding,
    ReflectionIssueType,
    ReflectionRequest,
    ReflectionSeverity,
)
from app.reflection.service import reflection_service
from app.schemas.tool_schema import ToolCallRecord


ACCEPTED_RETRIEVAL_STATUSES = {"OK", "DOC_FALLBACK"}
FAILED_RETRIEVAL_STATUSES = {"ERROR", "TIMEOUT", "UNAVAILABLE", "DEGRADED_ERROR"}
POLICY_TERMS = (
    "能退", "可以退", "能退款", "可以退款", "怎么退", "退货", "退款", "换货",
    "售后政策", "售后规则", "售后资格", "能取消", "可以取消", "取消订单", "运费",
    "多久", "什么时候", "时效", "自动关闭", "发货", "物流", "长时间没更新", "到账", "规则", "政策",
)
STATUS_ALIASES = {
    "待付款": "待支付",
    "待支付": "待支付",
    "待发货": "待发货",
    "已发货": "已发货",
    "待收货": "已发货",
    "已完成": "已完成",
    "已经完成": "已完成",
    "已关闭": "已关闭",
    "已经关闭": "已关闭",
    "已取消": "已关闭",
    "已经取消": "已关闭",
    "无效订单": "无效订单",
    "待审核": "待审核",
    "审核中": "待审核",
    "已通过": "已通过",
    "已拒绝": "已拒绝",
    "退款中": "退款中",
    "已退款": "已退款",
    "处理中": "处理中",
}
WRITE_TOOL_PATTERNS = (
    (re.compile(r"(?:加入|加到|放入).{0,4}购物车"), "add_to_cart_confirmed"),
    (re.compile(r"(?:领取|领一下|帮我领).{0,6}(?:优惠券|券)"), "claim_coupon_confirmed"),
    (re.compile(r"(?:(?:帮我|立即|现在|我要)取消.{0,4}订单|^取消.{0,4}订单\s*AIM\d+)"), "cancel_order_confirmed"),
    (re.compile(r"(?:帮我|立即|现在|我要)(?:申请|提交).{0,6}(?:售后|退货|退款)"), "apply_return_confirmed"),
)
READ_TOOL_GROUPS = {
    "RECOMMENDATION": ({"search_products"},),
    "PRODUCT_QA": ({"search_products", "get_product_detail", "get_product_skus", "compare_products"},),
    "ORDER_QA": ({"list_my_orders", "get_my_order_detail"},),
    "COUPON_QA": ({"list_my_coupons", "list_coupon_center"},),
    "RETURN_QA": ({"list_my_returns", "get_return_detail"},),
    "ADDRESS_QA": ({"list_my_addresses"},),
}


class DeterministicReflectionValidator:
    def evaluate(
        self,
        *,
        query: str,
        intent: str,
        answer: str,
        trace_id: str,
        evidence_payload: dict[str, Any] | None,
        tool_calls: list[ToolCallRecord] | None = None,
        page_context: dict[str, Any] | None = None,
        attempt: int = 0,
        max_attempts: int = 1,
        check_answer: bool = True,
    ) -> ReflectionDecision:
        payload = evidence_payload or {}
        citations = self._dict_items(payload.get("citations"))
        evidence = self._dict_items(payload.get("evidence"))
        business_evidence = self._dict_items(payload.get("businessEvidence"))
        records = list(tool_calls or [])
        request = ReflectionRequest(
            query=query,
            intent=intent,
            answer=answer,
            traceId=trace_id,
            attempt=attempt,
            maxAttempts=max_attempts,
            hasEvidence=bool(citations or evidence),
            hasBusinessEvidence=bool(business_evidence),
        )
        findings: list[ReflectionFinding] = []

        if check_answer and not answer.strip():
            findings.append(
                self._finding(
                    ReflectionIssueType.EMPTY_ANSWER,
                    "候选答案为空。",
                    retryable=True,
                )
            )

        requested_write_tool = self._requested_write_tool(query)
        policy_searched = bool(payload.get("policySearched"))
        policy_required = not requested_write_tool and (
            intent == "POLICY_QA" or any(term in query for term in POLICY_TERMS)
        )
        retrieval_status = str(payload.get("retrievalStatus") or "NO_MATCH")
        if policy_required and (not policy_searched or not citations or retrieval_status not in ACCEPTED_RETRIEVAL_STATUSES):
            issue_type = (
                ReflectionIssueType.RETRIEVAL_FAILED
                if retrieval_status in FAILED_RETRIEVAL_STATUSES
                else ReflectionIssueType.MISSING_EVIDENCE
            )
            findings.append(
                self._finding(
                    issue_type,
                    "政策回答缺少可用证据。",
                    retryable=True,
                    metadata={"retrievalStatus": retrieval_status},
                )
            )

        findings.extend(self._validate_tool_execution(query, intent, records, page_context or {}))
        if check_answer:
            findings.extend(self._validate_business_facts(answer, records))
            findings.extend(self._validate_confirmation_state(answer, records))

            valid_policy_ids = {str(item.get("id")) for item in citations if item.get("id") is not None}
            valid_business_ids = {
                str(item.get("id")).upper() for item in business_evidence if item.get("id") is not None
            }
            used_policy_ids = set(re.findall(r"\[(\d+)]", answer))
            used_business_ids = {
                item.upper() for item in re.findall(r"\[(B\d+)]", answer, flags=re.IGNORECASE)
            }

            if citations and not used_policy_ids:
                findings.append(
                    self._finding(
                        ReflectionIssueType.MISSING_CITATION,
                        "政策答案未引用检索证据。",
                        retryable=True,
                        evidence_refs=sorted(valid_policy_ids),
                    )
                )

            invalid_ids = sorted(
                {item for item in used_policy_ids if item not in valid_policy_ids}
                | {item for item in used_business_ids if item not in valid_business_ids}
            )
            if invalid_ids:
                findings.append(
                    self._finding(
                        ReflectionIssueType.INVALID_CITATION,
                        "答案使用了不存在的引用编号。",
                        retryable=True,
                        evidence_refs=invalid_ids,
                        metadata={"invalidCitationIds": invalid_ids},
                    )
                )

            findings.extend(self._validate_citation_fact_binding(answer, citations))

            evidence_text = "\n".join(
                self._evidence_text(item) for item in [*citations, *evidence, *business_evidence]
            )
            evidence_text += "\n" + self._business_fact_evidence_text(business_evidence)
            answer_fact_tokens = extract_fact_tokens(answer)
            evidence_fact_tokens = extract_fact_tokens(evidence_text) | extract_fact_tokens(query)
            unsupported_facts = sorted(answer_fact_tokens - evidence_fact_tokens)
            if unsupported_facts and (citations or business_evidence):
                findings.append(
                    self._finding(
                        ReflectionIssueType.UNSUPPORTED_FACT,
                        "答案包含证据中不存在的数字或日期事实。",
                        retryable=True,
                        metadata={"unsupportedFacts": unsupported_facts[:10]},
                    )
                )

        return reflection_service.decide(request, findings)

    def _validate_citation_fact_binding(
        self,
        answer: str,
        citations: list[dict[str, Any]],
    ) -> list[ReflectionFinding]:
        citation_by_id = {
            str(item.get("id")): item
            for item in citations
            if item.get("id") is not None
        }
        findings: list[ReflectionFinding] = []
        for claim, citation_id in re.findall(r"([^。！？!?]{1,500}?)\[(\d+)]", answer):
            citation = citation_by_id.get(citation_id)
            if citation is None:
                continue
            claimed_facts = extract_fact_tokens(claim)
            if not claimed_facts:
                continue
            supported_facts = extract_fact_tokens(self._evidence_text(citation))
            unsupported = sorted(claimed_facts - supported_facts)
            if unsupported:
                findings.append(
                    self._finding(
                        ReflectionIssueType.UNSUPPORTED_FACT,
                        "带引用的数字或日期事实未被该引用本身支持。",
                        retryable=True,
                        evidence_refs=[citation_id],
                        metadata={
                            "citationId": citation_id,
                            "unsupportedFacts": unsupported[:10],
                            "scope": "CITATION_LOCAL",
                        },
                    )
                )
        return findings

    def _validate_tool_execution(
        self,
        query: str,
        intent: str,
        records: list[ToolCallRecord],
        page_context: dict[str, Any],
    ) -> list[ReflectionFinding]:
        findings: list[ReflectionFinding] = []
        successful = {record.name for record in records if record.ok}
        for record in records:
            if record.ok:
                continue
            error = str(record.error or "")
            retryable = any(term in error.lower() for term in ("timeout", "timed out", "network", "connection", "暂时"))
            findings.append(
                self._finding(
                    ReflectionIssueType.TOOL_FAILURE,
                    f"工具 {record.name} 调用失败。",
                    retryable=retryable,
                    metadata={"toolName": record.name},
                )
            )

        write_tool = self._requested_write_tool(query)
        required_groups: list[set[str]] = []
        if write_tool:
            required_groups.append({write_tool})
        else:
            required_groups.extend(READ_TOOL_GROUPS.get(intent, ()))
            if intent == "PRODUCT_QA" and any(term in query for term in ("对比", "比较", "区别", "哪个更好", "哪款更好")):
                required_groups = [{"compare_products"}]

        if (intent == "POLICY_QA" or any(term in query for term in POLICY_TERMS)) and not write_tool:
            required_groups.append({"search_policy_kb"})

        for group in required_groups:
            if successful & group:
                continue
            findings.append(
                self._finding(
                    ReflectionIssueType.TASK_INCOMPLETE,
                    "回答前缺少必需的业务工具结果。",
                    retryable=True,
                    metadata={"requiredAnyOf": sorted(group), "pageType": page_context.get("pageType")},
                )
            )
        return findings

    def _requested_write_tool(self, query: str) -> str | None:
        return next((name for pattern, name in WRITE_TOOL_PATTERNS if pattern.search(query)), None)

    def _validate_business_facts(
        self,
        answer: str,
        records: list[ToolCallRecord],
    ) -> list[ReflectionFinding]:
        business_results = [record.result for record in records if record.ok and record.name != "search_policy_kb"]
        if not business_results:
            return []
        serialized = json.dumps(business_results, ensure_ascii=False, default=str)
        actual_statuses = {normalized for raw, normalized in STATUS_ALIASES.items() if raw in serialized}
        claimed_statuses = {normalized for raw, normalized in STATUS_ALIASES.items() if raw in answer}
        conflicts: dict[str, Any] = {}
        if claimed_statuses and actual_statuses and claimed_statuses.isdisjoint(actual_statuses):
            conflicts["status"] = {
                "claimed": sorted(claimed_statuses),
                "observed": sorted(actual_statuses),
            }

        actual_money, actual_stock = self._collect_business_numbers(business_results)
        claimed_money = self._answer_money_values(answer)
        claimed_stock = self._answer_stock_values(answer)
        if claimed_money and actual_money and not claimed_money.issubset(actual_money):
            conflicts["money"] = {"claimed": sorted(claimed_money), "observed": sorted(actual_money)}
        if claimed_stock and actual_stock and not claimed_stock.issubset(actual_stock):
            conflicts["stock"] = {"claimed": sorted(claimed_stock), "observed": sorted(actual_stock)}
        if not conflicts:
            return []
        return [
            self._finding(
                ReflectionIssueType.BUSINESS_FACT_CONFLICT,
                "答案中的业务事实与工具结果冲突。",
                retryable=False,
                metadata=conflicts,
            )
        ]

    def _validate_confirmation_state(
        self,
        answer: str,
        records: list[ToolCallRecord],
    ) -> list[ReflectionFinding]:
        confirmed_records = [record for record in records if record.name.endswith("_confirmed") and record.ok]
        if not confirmed_records:
            return []
        malformed = []
        for record in confirmed_records:
            action = record.result.get("pendingAction") if isinstance(record.result, dict) else None
            if not isinstance(action, dict) or action.get("status") != "PENDING":
                malformed.append(record.name)
        completion_claim = re.search(
            r"(?:已|已经)(?:成功)?(?:加入购物车|领取优惠券|领券|取消订单|提交售后|申请退货|申请退款|完成退款)",
            answer,
        )
        if not malformed and not completion_claim:
            return []
        return [
            self._finding(
                ReflectionIssueType.CONFIRMATION_STATE_ERROR,
                "待确认操作被错误描述为已经执行，或待确认状态无效。",
                retryable=False,
                metadata={"tools": [record.name for record in confirmed_records], "malformed": malformed},
            )
        ]

    def _collect_business_numbers(self, values: list[Any]) -> tuple[set[str], set[str]]:
        money: set[str] = set()
        stock: set[str] = set()

        def visit(value: Any, key: str = "") -> None:
            if isinstance(value, dict):
                for child_key, child in value.items():
                    visit(child, str(child_key).lower())
            elif isinstance(value, list):
                for child in value:
                    visit(child, key)
            elif isinstance(value, (int, float, Decimal)) and not isinstance(value, bool):
                normalized = self._decimal(value)
                if any(term in key for term in ("price", "amount", "total", "pay")):
                    money.add(normalized)
                if any(term in key for term in ("stock", "inventory")):
                    stock.add(normalized)

        for value in values:
            visit(value)
        return money, stock

    def _answer_money_values(self, answer: str) -> set[str]:
        patterns = (
            r"(?:¥|￥)\s*(\d+(?:\.\d+)?)",
            r"(?:价格|售价|现价|单价|金额|合计|实付)[^\d]{0,6}(\d+(?:\.\d+)?)\s*元?",
        )
        return {self._decimal(match.group(1)) for pattern in patterns for match in re.finditer(pattern, answer)}

    def _answer_stock_values(self, answer: str) -> set[str]:
        return {
            self._decimal(match.group(1))
            for match in re.finditer(r"库存[^\d]{0,6}(\d+(?:\.\d+)?)\s*件?", answer)
        }

    def _decimal(self, value: Any) -> str:
        try:
            normalized = Decimal(str(value)).normalize()
        except InvalidOperation:
            return str(value)
        return format(normalized, "f")

    def _finding(
        self,
        issue_type: ReflectionIssueType,
        message: str,
        *,
        retryable: bool,
        evidence_refs: list[str] | None = None,
        metadata: dict[str, Any] | None = None,
    ) -> ReflectionFinding:
        return ReflectionFinding(
            issueType=issue_type,
            severity=ReflectionSeverity.HIGH,
            message=message,
            retryable=retryable,
            evidenceRefs=evidence_refs or [],
            metadata=metadata or {},
        )

    def _dict_items(self, value: Any) -> list[dict[str, Any]]:
        if not isinstance(value, list):
            return []
        return [item for item in value if isinstance(item, dict)]

    def _evidence_text(self, item: dict[str, Any]) -> str:
        parts = [
            item.get("snippet"),
            item.get("content"),
            item.get("title"),
            item.get("result"),
        ]
        return " ".join(
            json.dumps(part, ensure_ascii=False, default=str) if isinstance(part, (dict, list)) else str(part)
            for part in parts
            if part not in (None, "")
        )

    def _business_fact_evidence_text(self, business_evidence: list[dict[str, Any]]) -> str:
        money, stock = self._collect_business_numbers(
            [item.get("result") for item in business_evidence if item.get("result") is not None]
        )
        return " ".join(
            [*(f"{value}元" for value in sorted(money)), *(f"{value}件" for value in sorted(stock))]
        )


def extract_fact_tokens(text: str) -> set[str]:
    if not text:
        return set()
    without_citations = re.sub(r"\[(?:B)?\d+]", "", text, flags=re.IGNORECASE)
    patterns = (
        r"(?:19|20)\d{2}[-/.年]\d{1,2}(?:[-/.月]\d{1,2}日?)?",
        r"(?:¥|￥)\s*\d+(?:\.\d+)?",
        r"\d+(?:\.\d+)?\s*(?:天|日|小时|分钟|元|块|折|%|％|次|件|个|年|月)",
    )
    tokens: set[str] = set()
    for pattern in patterns:
        for match in re.finditer(pattern, without_citations):
            tokens.add(_normalize_fact_token(match.group(0)))
    return tokens


def _normalize_fact_token(value: str) -> str:
    token = re.sub(r"\s+", "", value)
    currency = re.fullmatch(r"(?:¥|￥)(\d+(?:\.\d+)?)", token)
    if currency:
        return f"{_normalize_decimal_literal(currency.group(1))}元"

    measured = re.fullmatch(r"(\d+(?:\.\d+)?)(天|日|小时|分钟|元|块|折|%|％|次|件|个|年|月)", token)
    if not measured:
        return token
    number = _normalize_decimal_literal(measured.group(1))
    unit = measured.group(2)
    if unit == "块":
        unit = "元"
    elif unit == "％":
        unit = "%"
    return f"{number}{unit}"


def _normalize_decimal_literal(value: str) -> str:
    try:
        return format(Decimal(value).normalize(), "f")
    except InvalidOperation:
        return value


reflection_validator = DeterministicReflectionValidator()
