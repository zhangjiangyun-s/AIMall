from __future__ import annotations

from enum import StrEnum
from typing import Any

from pydantic import BaseModel, Field, model_validator


class ReflectionStatus(StrEnum):
    PASSED = "PASSED"
    RETRY_REQUIRED = "RETRY_REQUIRED"
    CLARIFICATION_REQUIRED = "CLARIFICATION_REQUIRED"
    DEGRADED = "DEGRADED"
    HUMAN_REVIEW_REQUIRED = "HUMAN_REVIEW_REQUIRED"
    REFUSED = "REFUSED"


class ReflectionAction(StrEnum):
    ACCEPT = "ACCEPT"
    RETRY_RETRIEVAL = "RETRY_RETRIEVAL"
    RETRY_TOOL_EXECUTION = "RETRY_TOOL_EXECUTION"
    RETRY_GENERATION = "RETRY_GENERATION"
    REQUEST_CLARIFICATION = "REQUEST_CLARIFICATION"
    RETURN_EVIDENCE_ONLY = "RETURN_EVIDENCE_ONLY"
    HANDOFF_HUMAN = "HANDOFF_HUMAN"
    REFUSE = "REFUSE"


class ReflectionSeverity(StrEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class ReflectionIssueType(StrEnum):
    EMPTY_ANSWER = "EMPTY_ANSWER"
    USER_INTENT_AMBIGUOUS = "USER_INTENT_AMBIGUOUS"
    RETRIEVAL_FAILED = "RETRIEVAL_FAILED"
    MISSING_EVIDENCE = "MISSING_EVIDENCE"
    MISSING_CITATION = "MISSING_CITATION"
    INVALID_CITATION = "INVALID_CITATION"
    UNSUPPORTED_FACT = "UNSUPPORTED_FACT"
    EVIDENCE_CONTRADICTION = "EVIDENCE_CONTRADICTION"
    BUSINESS_FACT_CONFLICT = "BUSINESS_FACT_CONFLICT"
    TOOL_FAILURE = "TOOL_FAILURE"
    TASK_INCOMPLETE = "TASK_INCOMPLETE"
    ANSWER_INCOMPLETE = "ANSWER_INCOMPLETE"
    CONFIRMATION_STATE_ERROR = "CONFIRMATION_STATE_ERROR"
    FORMAT_VIOLATION = "FORMAT_VIOLATION"
    VALIDATOR_FAILURE = "VALIDATOR_FAILURE"


class ReflectionFinding(BaseModel):
    issueType: ReflectionIssueType
    severity: ReflectionSeverity
    message: str
    retryable: bool = False
    evidenceRefs: list[str] = Field(default_factory=list)
    metadata: dict[str, Any] = Field(default_factory=dict)


class ReflectionRequest(BaseModel):
    query: str
    intent: str
    answer: str
    traceId: str
    attempt: int = Field(default=0, ge=0)
    maxAttempts: int = Field(default=1, ge=0, le=3)
    hasEvidence: bool = False
    hasBusinessEvidence: bool = False


class ReflectionDecision(BaseModel):
    passed: bool
    status: ReflectionStatus
    action: ReflectionAction
    terminal: bool
    attempt: int = Field(ge=0)
    maxAttempts: int = Field(ge=0, le=3)
    findings: list[ReflectionFinding] = Field(default_factory=list)
    policyVersion: str

    @model_validator(mode="after")
    def validate_state(self) -> "ReflectionDecision":
        if self.passed:
            if self.status != ReflectionStatus.PASSED or self.action != ReflectionAction.ACCEPT:
                raise ValueError("passed decision must use PASSED/ACCEPT")
            if self.findings:
                raise ValueError("passed decision cannot contain findings")
        elif self.status == ReflectionStatus.PASSED or self.action == ReflectionAction.ACCEPT:
            raise ValueError("failed decision cannot use PASSED/ACCEPT")
        if self.status == ReflectionStatus.RETRY_REQUIRED and self.terminal:
            raise ValueError("retry decision cannot be terminal")
        if self.status != ReflectionStatus.RETRY_REQUIRED and not self.terminal:
            raise ValueError("non-retry decision must be terminal")
        return self
