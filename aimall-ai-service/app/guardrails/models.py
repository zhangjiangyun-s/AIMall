from __future__ import annotations

from enum import StrEnum
from typing import Any

from pydantic import BaseModel, Field


class GuardrailAction(StrEnum):
    ALLOW = "ALLOW"
    SANITIZE = "SANITIZE"
    BLOCK = "BLOCK"


class RiskLevel(StrEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class GuardrailFinding(BaseModel):
    ruleId: str
    category: str
    riskLevel: RiskLevel
    action: GuardrailAction
    message: str


class GuardrailDecision(BaseModel):
    allowed: bool
    action: GuardrailAction
    riskLevel: RiskLevel
    sanitizedText: str
    findings: list[GuardrailFinding] = Field(default_factory=list)
    policyVersion: str

    def public_summary(self) -> dict[str, object]:
        return {
            "allowed": self.allowed,
            "action": self.action.value,
            "riskLevel": self.riskLevel.value,
            "policyVersion": self.policyVersion,
            "ruleIds": [finding.ruleId for finding in self.findings],
            "categories": sorted({finding.category for finding in self.findings}),
        }


class ToolGuardrailDecision(BaseModel):
    allowed: bool
    action: GuardrailAction
    riskLevel: RiskLevel
    sanitizedArguments: dict[str, Any] = Field(default_factory=dict)
    findings: list[GuardrailFinding] = Field(default_factory=list)
    policyVersion: str

    def public_summary(self) -> dict[str, object]:
        return {
            "allowed": self.allowed,
            "action": self.action.value,
            "riskLevel": self.riskLevel.value,
            "policyVersion": self.policyVersion,
            "ruleIds": [finding.ruleId for finding in self.findings],
            "categories": sorted({finding.category for finding in self.findings}),
        }


class EvidenceGuardrailDecision(BaseModel):
    allowed: bool
    action: GuardrailAction
    riskLevel: RiskLevel
    sanitizedEvidence: dict[str, Any] = Field(default_factory=dict)
    findings: list[GuardrailFinding] = Field(default_factory=list)
    policyVersion: str

    def public_summary(self) -> dict[str, object]:
        evidence = self.sanitizedEvidence
        return {
            "allowed": self.allowed,
            "action": self.action.value,
            "riskLevel": self.riskLevel.value,
            "policyVersion": self.policyVersion,
            "ruleIds": [finding.ruleId for finding in self.findings],
            "docId": evidence.get("docId") or evidence.get("id"),
            "docVersionId": evidence.get("docVersionId"),
            "chunkId": evidence.get("chunkId"),
            "sourceType": evidence.get("sourceType"),
        }
