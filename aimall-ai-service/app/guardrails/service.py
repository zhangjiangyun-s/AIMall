from __future__ import annotations

import json
import re
from collections.abc import Iterable
from typing import Any

from app.config.settings import settings
from app.guardrails.models import (
    GuardrailAction,
    GuardrailDecision,
    GuardrailFinding,
    EvidenceGuardrailDecision,
    RiskLevel,
    ToolGuardrailDecision,
)


POLICY_VERSION = "GUARDRAILS_V1"

_RISK_ORDER = {
    RiskLevel.LOW: 0,
    RiskLevel.MEDIUM: 1,
    RiskLevel.HIGH: 2,
    RiskLevel.CRITICAL: 3,
}

_DIRECT_INJECTION_PATTERNS = (
    re.compile(r"(?i)(ignore|disregard|forget)\s+(all\s+)?(previous|prior|system|developer)\s+(instructions?|prompts?|rules?)"),
    re.compile(r"(?:忽略|无视|忘掉|覆盖).{0,16}(?:系统|开发者|之前|以上|原有).{0,12}(?:指令|提示词|规则|要求)"),
    re.compile(r"(?:输出|泄露|展示|告诉我|打印).{0,16}(?:system\s*prompt|系统提示词|开发者指令|隐藏指令|内部提示词)"),
    re.compile(r"(?i)(reveal|print|show|leak).{0,24}(system\s*prompt|developer\s*message|hidden\s*instructions?)"),
)

_CONFIRMATION_BYPASS_PATTERNS = (
    re.compile(r"(?:绕过|跳过|无需|不要|取消).{0,10}(?:确认|审批|授权).{0,20}(?:执行|取消订单|退款|售后|加入购物车|领券)"),
    re.compile(r"(?i)(bypass|skip|without).{0,12}(confirmation|approval).{0,24}(execute|cancel|refund|claim|add)"),
)

_SECURITY_BYPASS_PATTERNS = (
    re.compile(r"(?:关闭|禁用|绕过|移除).{0,12}(?:安全|护栏|权限|鉴权|风控|guardrail)"),
    re.compile(r"(?i)(disable|bypass|remove).{0,12}(guardrails?|security|authorization|permission checks?)"),
)

_IDENTITY_SPOOF_PATTERNS = (
    re.compile(r"(?i)\b(?:member_?id|user_?id|customer_?id)\s*(?:=|:|：)?\s*\d+.{0,24}(?:查询|查看|获取|列出|订单|售后|地址)"),
    re.compile(r"(?:使用|用|指定|伪造|冒充).{0,8}(?:member_?id|user_?id|用户ID|会员ID).{0,16}(?:查询|查看|获取|列出|订单|售后|地址)", re.IGNORECASE),
)

_INDIRECT_INJECTION_PATTERNS = (
    (
        "RAG_INSTRUCTION_OVERRIDE",
        re.compile(r"(?i)(ignore|disregard|forget).{0,24}(previous|system|developer).{0,20}(instructions?|prompts?|rules?)|(?:忽略|无视|覆盖).{0,20}(?:系统|开发者|之前|以上).{0,16}(?:指令|提示词|规则)"),
    ),
    (
        "RAG_PROMPT_EXFILTRATION",
        re.compile(r"(?i)(reveal|print|show|leak).{0,30}(system\s*prompt|developer\s*message|hidden\s*instructions?)|(?:输出|泄露|展示|打印).{0,20}(?:系统提示词|开发者指令|隐藏指令|内部提示词)"),
    ),
    (
        "RAG_TOOL_EXECUTION_DIRECTIVE",
        re.compile(r"(?i)(call|execute|invoke).{0,20}(tool|function|api)|(?:调用|执行).{0,16}(?:工具|函数|接口).{0,20}(?:无需|绕过|跳过)?(?:确认|权限|鉴权)?"),
    ),
    (
        "RAG_DATA_EXFILTRATION_DIRECTIVE",
        re.compile(r"(?i)(send|upload|exfiltrate).{0,30}(token|password|secret|customer\s*data)|(?:发送|上传|外传|窃取).{0,24}(?:Token|密码|密钥|用户数据|客户数据)"),
    ),
    (
        "RAG_ROLE_MARKUP_INJECTION",
        re.compile(r"(?i)<\s*(system|assistant|developer)\s*>|\[\s*(system|developer)\s*\]|(?:当你|如果你).{0,18}(?:读到|看到|检索到).{0,16}(?:本文|这段|此内容).{0,16}(?:必须|立即|请)"),
    ),
)

_PROTECTIVE_INSTRUCTION_PATTERN = re.compile(
    r"(?:不得|禁止|不能|不应|严禁|防止|避免).{0,8}(?:输出|泄露|展示|打印|忽略|绕过|调用|执行|发送|上传|外传)"
)

_SECRET_PATTERNS = (
    ("SECRET_API_KEY", re.compile(r"\bsk-[A-Za-z0-9_-]{12,}\b"), "检测到 API Key，请立即撤销并更换该密钥。"),
    ("SECRET_BEARER_TOKEN", re.compile(r"(?i)\bbearer\s+[A-Za-z0-9._~+/-]{12,}={0,2}\b"), "检测到登录凭据，请不要在聊天中提交 Token。"),
    ("SECRET_PASSWORD", re.compile(r"(?i)(?:密码|支付密码|password|passwd)\s*[:：=]\s*\S{4,}"), "检测到密码信息，请不要在聊天中提交密码。"),
    ("SECRET_OTP", re.compile(r"(?:验证码|短信码|动态码|otp|verification\s*code)\s*[:：=]?\s*\d{4,8}", re.I), "检测到验证码，请不要向任何人或 AI 提供验证码。"),
)

_PHONE_PATTERN = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
_ID_CARD_PATTERN = re.compile(r"(?<![0-9A-Za-z])\d{17}[0-9Xx](?![0-9A-Za-z])")
_EMAIL_PATTERN = re.compile(r"(?i)(?<![\w.+-])[\w.+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}(?![\w.-])")
_BANK_CARD_PATTERN = re.compile(r"(?<![0-9A-Za-z])(?:\d[ -]?){15,18}\d(?![0-9A-Za-z])")
_OPAQUE_TOKEN_PATTERN = re.compile(r"(?<![A-Za-z0-9])[A-Za-z0-9._~+/-]{40,}(?![A-Za-z0-9])")
_STREAM_BOUNDARY_PATTERN = re.compile(r"[。！？，；,;!?\n]|(?<=\.)\s")
_SENSITIVE_KEYS = {
    "password",
    "passwd",
    "paymentpassword",
    "payment_password",
    "token",
    "authtoken",
    "auth_token",
    "accesstoken",
    "access_token",
    "authorization",
    "authcontext",
    "api_key",
    "apikey",
    "secret",
    "verificationcode",
    "verification_code",
    "otp",
}
_STRUCTURED_IDENTIFIER_KEYS = {
    "traceid",
    "actionid",
    "contenthash",
    "chunkkey",
    "docversionid",
    "ownerfingerprint",
    "sessionidhash",
    "sourcehash",
    "source_hash",
    "embeddingid",
}


class GuardrailService:
    def evaluate_input(self, text: str) -> GuardrailDecision:
        normalized = self._normalize(text)
        sanitized = normalized
        findings: list[GuardrailFinding] = []

        if len(normalized) > settings.GUARDRAIL_MAX_INPUT_CHARS:
            findings.append(
                self._finding(
                    "INPUT_LENGTH_EXCEEDED",
                    "INPUT_VALIDATION",
                    RiskLevel.HIGH,
                    GuardrailAction.BLOCK,
                    f"输入内容过长，最多允许 {settings.GUARDRAIL_MAX_INPUT_CHARS} 个字符。",
                )
            )

        self._append_pattern_finding(
            findings,
            normalized,
            _DIRECT_INJECTION_PATTERNS,
            "PROMPT_INJECTION_DIRECT",
            "PROMPT_INJECTION",
            RiskLevel.HIGH,
            "请求试图覆盖系统指令或获取内部提示信息。",
        )
        self._append_pattern_finding(
            findings,
            normalized,
            _CONFIRMATION_BYPASS_PATTERNS,
            "HITL_CONFIRMATION_BYPASS",
            "AUTHORIZATION_BYPASS",
            RiskLevel.CRITICAL,
            "高风险业务操作不能绕过用户确认。",
        )
        self._append_pattern_finding(
            findings,
            normalized,
            _SECURITY_BYPASS_PATTERNS,
            "SECURITY_CONTROL_BYPASS",
            "AUTHORIZATION_BYPASS",
            RiskLevel.CRITICAL,
            "请求试图关闭或绕过安全控制。",
        )
        self._append_pattern_finding(
            findings,
            normalized,
            _IDENTITY_SPOOF_PATTERNS,
            "INPUT_IDENTITY_SPOOFING",
            "AUTHORIZATION_BYPASS",
            RiskLevel.CRITICAL,
            "身份参数只能由服务端根据当前登录凭据注入。",
        )

        for rule_id, pattern, message in _SECRET_PATTERNS:
            if pattern.search(normalized):
                findings.append(
                    self._finding(
                        rule_id,
                        "CREDENTIAL_EXPOSURE",
                        RiskLevel.CRITICAL,
                        GuardrailAction.BLOCK,
                        message,
                    )
                )
                sanitized = pattern.sub("[REDACTED_CREDENTIAL]", sanitized)

        if _BANK_CARD_PATTERN.search(normalized):
            findings.append(
                self._finding(
                    "PAYMENT_CARD_EXPOSURE",
                    "PAYMENT_DATA",
                    RiskLevel.CRITICAL,
                    GuardrailAction.BLOCK,
                    "检测到疑似银行卡号，请不要在聊天中提交完整支付卡信息。",
                )
            )
            sanitized = _BANK_CARD_PATTERN.sub("[REDACTED_BANK_CARD]", sanitized)

        sanitized, pii_findings = self._redact_pii(sanitized)
        findings.extend(pii_findings)
        action = self._max_action(findings)
        risk = self._max_risk(findings)
        return GuardrailDecision(
            allowed=action != GuardrailAction.BLOCK,
            action=action,
            riskLevel=risk,
            sanitizedText=sanitized,
            findings=findings,
            policyVersion=POLICY_VERSION,
        )

    def refusal_message(self, decision: GuardrailDecision) -> str:
        categories = {finding.category for finding in decision.findings}
        if "CREDENTIAL_EXPOSURE" in categories or "PAYMENT_DATA" in categories:
            return "为保护账户和资金安全，请不要在聊天中提供密码、验证码、Token、API Key 或完整银行卡号。请先撤销已经暴露的凭据，并通过商城的安全页面处理相关业务。"
        if "AUTHORIZATION_BYPASS" in categories:
            return "为保护账户安全，我仅支持已登录用户本人数据，无法直接使用 memberId、userId 等身份参数查询他人信息，也不能绕过权限校验或用户确认。"
        if "PROMPT_INJECTION" in categories:
            return "这条请求试图获取或覆盖系统内部指令，我不能按该方式处理。你可以直接描述需要查询的商品、订单或商城政策问题。"
        return "这条请求未通过安全检查，请缩短内容并移除敏感信息后重试。"

    def redact_text(self, text: str) -> tuple[str, list[GuardrailFinding]]:
        sanitized = str(text or "").replace("\x00", "")
        findings: list[GuardrailFinding] = []
        for rule_id, pattern, message in _SECRET_PATTERNS:
            if not pattern.search(sanitized):
                continue
            sanitized = pattern.sub("[REDACTED_CREDENTIAL]", sanitized)
            findings.append(
                self._finding(
                    rule_id,
                    "CREDENTIAL_EXPOSURE",
                    RiskLevel.CRITICAL,
                    GuardrailAction.SANITIZE,
                    message,
                )
            )
        if _BANK_CARD_PATTERN.search(sanitized):
            sanitized = _BANK_CARD_PATTERN.sub("[REDACTED_BANK_CARD]", sanitized)
            findings.append(
                self._finding(
                    "PAYMENT_CARD_REDACTED",
                    "PAYMENT_DATA",
                    RiskLevel.CRITICAL,
                    GuardrailAction.SANITIZE,
                    "完整银行卡号已脱敏。",
                )
            )
        if _OPAQUE_TOKEN_PATTERN.search(sanitized):
            sanitized = _OPAQUE_TOKEN_PATTERN.sub("[REDACTED_OPAQUE_TOKEN]", sanitized)
            findings.append(
                self._finding(
                    "OPAQUE_TOKEN_REDACTED",
                    "CREDENTIAL_EXPOSURE",
                    RiskLevel.HIGH,
                    GuardrailAction.SANITIZE,
                    "疑似长凭据已脱敏。",
                )
            )
        sanitized, pii_findings = self._redact_pii(sanitized)
        findings.extend(pii_findings)
        return sanitized, findings

    def sanitize_payload(self, value: Any) -> Any:
        if isinstance(value, dict):
            sanitized: dict[str, Any] = {}
            for key, item in value.items():
                normalized_key = str(key).replace("-", "_").lower()
                if normalized_key in _SENSITIVE_KEYS:
                    sanitized[key] = "[REDACTED]"
                elif normalized_key in _STRUCTURED_IDENTIFIER_KEYS:
                    sanitized[key] = item
                else:
                    sanitized[key] = self.sanitize_payload(item)
            return sanitized
        if isinstance(value, list):
            return [self.sanitize_payload(item) for item in value]
        if isinstance(value, tuple):
            return [self.sanitize_payload(item) for item in value]
        if isinstance(value, str):
            return self.redact_text(value)[0]
        return value

    def evaluate_tool_call(
        self,
        tool: Any,
        arguments: dict[str, Any],
        auth_token: str | None,
    ) -> ToolGuardrailDecision:
        findings: list[GuardrailFinding] = []
        sanitized_arguments = self._sanitize_tool_value(arguments, findings)

        if tool is None:
            findings.append(
                self._finding(
                    "TOOL_NOT_REGISTERED",
                    "TOOL_ALLOWLIST",
                    RiskLevel.CRITICAL,
                    GuardrailAction.BLOCK,
                    "工具未注册，拒绝执行。",
                )
            )
        else:
            if bool(getattr(tool, "requiresAuth", False)) and not auth_token:
                findings.append(
                    self._finding(
                        "TOOL_AUTH_REQUIRED",
                        "TOOL_AUTHORIZATION",
                        RiskLevel.HIGH,
                        GuardrailAction.BLOCK,
                        "该工具需要登录身份。",
                    )
                )
            if str(getattr(tool, "risk", "LOW")) == "HIGH" and not bool(
                getattr(tool, "requiresConfirmation", False)
            ):
                findings.append(
                    self._finding(
                        "HIGH_RISK_TOOL_MISSING_CONFIRMATION",
                        "TOOL_CONFIGURATION",
                        RiskLevel.CRITICAL,
                        GuardrailAction.BLOCK,
                        "高风险工具必须启用用户确认。",
                    )
                )

            allowed_properties = set(
                ((getattr(tool, "parameters", {}) or {}).get("properties") or {}).keys()
            )
            if set(arguments.keys()) - allowed_properties:
                findings.append(
                    self._finding(
                        "TOOL_UNKNOWN_ARGUMENT",
                        "TOOL_ARGUMENTS",
                        RiskLevel.HIGH,
                        GuardrailAction.BLOCK,
                        "工具参数包含 Schema 未声明字段。",
                    )
                )

        if self._find_forbidden_identity_keys(arguments):
            findings.append(
                self._finding(
                    "TOOL_IDENTITY_ARGUMENT_FORBIDDEN",
                    "TOOL_AUTHORIZATION",
                    RiskLevel.CRITICAL,
                    GuardrailAction.BLOCK,
                    "模型不能通过工具参数传入用户身份或认证凭据。",
                )
            )

        serialized = json.dumps(arguments, ensure_ascii=False, default=str)
        if len(serialized) > settings.GUARDRAIL_MAX_TOOL_ARGUMENT_CHARS:
            findings.append(
                self._finding(
                    "TOOL_ARGUMENT_SIZE_EXCEEDED",
                    "TOOL_ARGUMENTS",
                    RiskLevel.HIGH,
                    GuardrailAction.BLOCK,
                    "工具参数超过允许大小。",
                )
            )

        action = self._max_action(findings)
        return ToolGuardrailDecision(
            allowed=action != GuardrailAction.BLOCK,
            action=action,
            riskLevel=self._max_risk(findings),
            sanitizedArguments=sanitized_arguments if isinstance(sanitized_arguments, dict) else {},
            findings=findings,
            policyVersion=POLICY_VERSION,
        )

    def evaluate_evidence(self, evidence: dict[str, Any]) -> EvidenceGuardrailDecision:
        safe_evidence = self.sanitize_payload(evidence)
        findings: list[GuardrailFinding] = []
        declared_risk = str(
            evidence.get("promptRiskLevel")
            or evidence.get("prompt_risk_level")
            or evidence.get("riskLevel")
            or "LOW"
        ).upper()
        if declared_risk in {"MEDIUM", "HIGH", "CRITICAL"}:
            findings.append(
                self._finding(
                    "RAG_DECLARED_PROMPT_RISK",
                    "INDIRECT_PROMPT_INJECTION",
                    RiskLevel.CRITICAL if declared_risk in {"HIGH", "CRITICAL"} else RiskLevel.HIGH,
                    GuardrailAction.BLOCK,
                    "知识证据已被入库流程标记为 Prompt 注入风险。",
                )
            )

        searchable_text = "\n".join(
            str(evidence.get(key) or "")
            for key in (
                "title",
                "sectionTitle",
                "sectionPath",
                "snippet",
                "content",
                "maskedContent",
                "indexContent",
            )
        )[: settings.GUARDRAIL_MAX_EVIDENCE_CHARS]
        for rule_id, pattern in _INDIRECT_INJECTION_PATTERNS:
            for match in pattern.finditer(searchable_text):
                if self._is_protective_instruction(searchable_text, match.start()):
                    continue
                findings.append(
                    self._finding(
                        rule_id,
                        "INDIRECT_PROMPT_INJECTION",
                        RiskLevel.CRITICAL,
                        GuardrailAction.BLOCK,
                        "知识证据包含可能控制 Agent 行为的指令，已隔离。",
                    )
                )
                break

        action = self._max_action(findings)
        return EvidenceGuardrailDecision(
            allowed=action != GuardrailAction.BLOCK,
            action=action,
            riskLevel=self._max_risk(findings),
            sanitizedEvidence=safe_evidence if isinstance(safe_evidence, dict) else {},
            findings=findings,
            policyVersion=POLICY_VERSION,
        )

    def filter_evidence(
        self,
        evidence_items: list[dict[str, Any]],
    ) -> tuple[list[dict[str, Any]], list[dict[str, object]]]:
        allowed: list[dict[str, Any]] = []
        blocked: list[dict[str, object]] = []
        for item in evidence_items:
            if not isinstance(item, dict):
                continue
            decision = self.evaluate_evidence(item)
            if decision.allowed:
                allowed.append(decision.sanitizedEvidence)
            else:
                blocked.append(decision.public_summary())
        return allowed, blocked

    def _redact_pii(self, text: str) -> tuple[str, list[GuardrailFinding]]:
        findings: list[GuardrailFinding] = []
        rules = (
            ("PII_PHONE_REDACTED", "PHONE", _PHONE_PATTERN, "[REDACTED_PHONE]"),
            ("PII_ID_CARD_REDACTED", "IDENTITY", _ID_CARD_PATTERN, "[REDACTED_ID_CARD]"),
            ("PII_EMAIL_REDACTED", "EMAIL", _EMAIL_PATTERN, "[REDACTED_EMAIL]"),
        )
        sanitized = text
        for rule_id, pii_type, pattern, replacement in rules:
            if not pattern.search(sanitized):
                continue
            sanitized = pattern.sub(replacement, sanitized)
            findings.append(
                self._finding(
                    rule_id,
                    "PII",
                    RiskLevel.MEDIUM,
                    GuardrailAction.SANITIZE,
                    f"检测到{pii_type}信息，已在进入 Agent 前脱敏。",
                )
            )
        return sanitized, findings

    def _normalize(self, text: str) -> str:
        value = str(text or "").replace("\x00", "")
        value = re.sub(r"[\r\t]+", " ", value)
        return re.sub(r" {3,}", "  ", value).strip()

    def _sanitize_tool_value(self, value: Any, findings: list[GuardrailFinding]) -> Any:
        if isinstance(value, dict):
            return {key: self._sanitize_tool_value(item, findings) for key, item in value.items()}
        if isinstance(value, list):
            return [self._sanitize_tool_value(item, findings) for item in value]
        if not isinstance(value, str):
            return value
        decision = self.evaluate_input(value)
        findings.extend(decision.findings)
        return decision.sanitizedText

    def _find_forbidden_identity_keys(self, value: Any) -> set[str]:
        forbidden = {
            "userid",
            "user_id",
            "memberid",
            "member_id",
            "ownerid",
            "owner_id",
            "token",
            "authtoken",
            "auth_token",
            "authorization",
        }
        matches: set[str] = set()
        if isinstance(value, dict):
            for key, item in value.items():
                normalized = str(key).replace("-", "_").lower()
                if normalized in forbidden:
                    matches.add(normalized)
                matches.update(self._find_forbidden_identity_keys(item))
        elif isinstance(value, list):
            for item in value:
                matches.update(self._find_forbidden_identity_keys(item))
        return matches

    def _is_protective_instruction(self, text: str, match_start: int) -> bool:
        window = text[max(0, match_start - 24) : match_start + 12]
        return bool(_PROTECTIVE_INSTRUCTION_PATTERN.search(window))

    def _append_pattern_finding(
        self,
        findings: list[GuardrailFinding],
        text: str,
        patterns: Iterable[re.Pattern[str]],
        rule_id: str,
        category: str,
        risk: RiskLevel,
        message: str,
    ) -> None:
        if any(pattern.search(text) for pattern in patterns):
            findings.append(self._finding(rule_id, category, risk, GuardrailAction.BLOCK, message))

    def _finding(
        self,
        rule_id: str,
        category: str,
        risk: RiskLevel,
        action: GuardrailAction,
        message: str,
    ) -> GuardrailFinding:
        return GuardrailFinding(
            ruleId=rule_id,
            category=category,
            riskLevel=risk,
            action=action,
            message=message,
        )

    def _max_action(self, findings: list[GuardrailFinding]) -> GuardrailAction:
        if any(item.action == GuardrailAction.BLOCK for item in findings):
            return GuardrailAction.BLOCK
        if any(item.action == GuardrailAction.SANITIZE for item in findings):
            return GuardrailAction.SANITIZE
        return GuardrailAction.ALLOW

    def _max_risk(self, findings: list[GuardrailFinding]) -> RiskLevel:
        if not findings:
            return RiskLevel.LOW
        return max((item.riskLevel for item in findings), key=lambda item: _RISK_ORDER[item])


guardrail_service = GuardrailService()


class StreamingRedactor:
    def __init__(self, service: GuardrailService | None = None) -> None:
        self.service = service or guardrail_service
        self._buffer = ""
        self._findings: dict[str, GuardrailFinding] = {}

    def feed(self, chunk: str) -> str:
        self._buffer += str(chunk or "")
        boundaries = list(_STREAM_BOUNDARY_PATTERN.finditer(self._buffer))
        if not boundaries:
            return ""
        split_at = boundaries[-1].end()
        ready = self._buffer[:split_at]
        self._buffer = self._buffer[split_at:]
        return self._redact(ready)

    def finish(self) -> str:
        ready = self._buffer
        self._buffer = ""
        return self._redact(ready) if ready else ""

    @property
    def findings(self) -> list[GuardrailFinding]:
        return list(self._findings.values())

    def public_summary(self) -> dict[str, object] | None:
        findings = self.findings
        if not findings:
            return None
        risk = max((item.riskLevel for item in findings), key=lambda item: _RISK_ORDER[item])
        return {
            "allowed": True,
            "action": GuardrailAction.SANITIZE.value,
            "riskLevel": risk.value,
            "policyVersion": POLICY_VERSION,
            "ruleIds": [item.ruleId for item in findings],
            "categories": sorted({item.category for item in findings}),
        }

    def _redact(self, text: str) -> str:
        sanitized, findings = self.service.redact_text(text)
        for finding in findings:
            self._findings.setdefault(finding.ruleId, finding)
        return sanitized
