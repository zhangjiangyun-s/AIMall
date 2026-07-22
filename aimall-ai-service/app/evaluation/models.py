from __future__ import annotations

from datetime import datetime
from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, Field, model_validator


EVALUATION_SCHEMA_VERSION = "AIMALL_EVAL_CASE_V1"
MANIFEST_SCHEMA_VERSION = "AIMALL_EVAL_MANIFEST_V1"


class EvaluationCategory(StrEnum):
    POLICY = "POLICY"
    PRODUCT = "PRODUCT"
    ORDER = "ORDER"
    RETURN = "RETURN"
    REFUSAL = "REFUSAL"
    AUTHORIZATION = "AUTHORIZATION"
    POLICY_RAG = "POLICY_RAG"
    HYBRID = "HYBRID"
    MEMORY = "MEMORY"
    PERMISSION = "PERMISSION"
    GUARDRAIL = "GUARDRAIL"
    HITL = "HITL"
    TOOL_FAILURE = "TOOL_FAILURE"
    REFLECTION = "REFLECTION"


class EvaluationRiskLevel(StrEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class ConversationTurn(BaseModel):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=4000)


class FaultInjection(BaseModel):
    toolName: str = Field(min_length=1)
    behavior: Literal["TIMEOUT", "ERROR", "EMPTY_RESULT"]
    errorCode: str | None = None


class EvaluationFixture(BaseModel):
    userFixture: str | None = None
    authRequired: bool = False
    pageContext: dict[str, Any] = Field(default_factory=lambda: {"pageType": "GENERAL"})
    history: list[ConversationTurn] = Field(default_factory=list, max_length=20)
    faultInjections: list[FaultInjection] = Field(default_factory=list, max_length=5)

    @model_validator(mode="after")
    def validate_auth_fixture(self) -> "EvaluationFixture":
        if self.authRequired and not self.userFixture:
            raise ValueError("authenticated evaluation cases must define userFixture")
        if self.history and self.history[0].role != "user":
            raise ValueError("evaluation history must start with a user turn")
        if any(left.role == right.role for left, right in zip(self.history, self.history[1:])):
            raise ValueError("evaluation history roles must alternate")
        return self


class GroundTruthFact(BaseModel):
    statement: str = Field(min_length=1, max_length=1000)
    sourceType: Literal["USER", "BUSINESS_TOOL", "POLICY", "SECURITY_POLICY", "EXPECTED_BEHAVIOR"]
    sourceRef: str | None = None


class SemanticRubric(BaseModel):
    criterion: str = Field(min_length=1, max_length=500)
    weight: float = Field(gt=0, le=1)
    required: bool = True


class RagRelevantEvidence(BaseModel):
    id: str = Field(pattern=r"^[a-z0-9][a-z0-9_-]+$")
    grade: int = Field(ge=1, le=3)
    sourcePrefixesAnyOf: list[str] = Field(default_factory=list)
    titleTermsAnyOf: list[str] = Field(default_factory=list)
    snippetTermsAnyOf: list[str] = Field(default_factory=list)

    @model_validator(mode="after")
    def validate_matcher(self) -> "RagRelevantEvidence":
        if not self.sourcePrefixesAnyOf and not self.titleTermsAnyOf:
            raise ValueError("relevant evidence must define a source or title matcher")
        return self


class RagExpectation(BaseModel):
    k: int = Field(default=5, ge=1, le=20)
    relevantEvidence: list[RagRelevantEvidence] = Field(default_factory=list, max_length=20)
    noMatchExpected: bool = False
    faithfulnessMinOverlap: float = Field(default=0.12, ge=0, le=1)
    minRecallAtK: float = Field(default=1.0, ge=0, le=1)
    minMrr: float = Field(default=1.0, ge=0, le=1)
    minNdcg: float = Field(default=0.8, ge=0, le=1)
    minCitationAccuracy: float = Field(default=1.0, ge=0, le=1)
    minCitationCoverage: float = Field(default=1.0, ge=0, le=1)
    minCitationFaithfulness: float = Field(default=1.0, ge=0, le=1)

    @model_validator(mode="after")
    def validate_relevance_contract(self) -> "RagExpectation":
        ids = [item.id for item in self.relevantEvidence]
        if len(ids) != len(set(ids)):
            raise ValueError("RAG relevant evidence ids must be unique")
        if self.noMatchExpected and self.relevantEvidence:
            raise ValueError("no-match cases cannot define relevant evidence")
        if not self.noMatchExpected and not self.relevantEvidence:
            raise ValueError("RAG retrieval cases must define relevant evidence")
        return self


class MemoryExpectation(BaseModel):
    entityKind: str = Field(min_length=1)
    referenceOrdinal: int = Field(ge=1, le=20)
    reuseTool: str = Field(min_length=1)
    toolArgument: str = Field(min_length=1)


class EvaluationExpectation(BaseModel):
    intentAnyOf: list[str] = Field(default_factory=list)
    requiredTools: list[str] = Field(default_factory=list)
    requiredSuccessfulTools: list[str] = Field(default_factory=list)
    forbiddenTools: list[str] = Field(default_factory=list)
    requireBusinessEvidence: bool = False
    requireCitations: bool = False
    citationTermsAnyOf: list[str] = Field(default_factory=list)
    retrievalStatusAnyOf: list[str] = Field(default_factory=list)
    answerMustContainAnyOf: list[str] = Field(default_factory=list)
    answerMustNotContain: list[str] = Field(default_factory=list)
    reflectionStatusAnyOf: list[str] = Field(default_factory=list)
    reflectionActionAnyOf: list[str] = Field(default_factory=list)
    guardrailAction: Literal["ALLOW", "SANITIZE", "BLOCK"] | None = None
    pendingActionExpected: bool | None = None
    refusalExpected: bool | None = None
    maxToolCalls: int | None = Field(default=None, ge=0, le=30)
    rag: RagExpectation | None = None
    memory: MemoryExpectation | None = None

    @model_validator(mode="after")
    def validate_tool_contract(self) -> "EvaluationExpectation":
        required = set(self.requiredTools)
        successful = set(self.requiredSuccessfulTools)
        forbidden = set(self.forbiddenTools)
        if required & forbidden:
            raise ValueError("requiredTools and forbiddenTools cannot overlap")
        if not successful.issubset(required):
            raise ValueError("requiredSuccessfulTools must be a subset of requiredTools")
        if self.requireCitations and "search_policy_kb" not in required:
            raise ValueError("citation cases must require search_policy_kb")
        if self.pendingActionExpected is True and not any(name.endswith("_confirmed") for name in required):
            raise ValueError("pending action cases must require a confirmation tool")
        if self.rag is not None and "search_policy_kb" not in required:
            raise ValueError("RAG metric cases must require search_policy_kb")
        if self.memory is not None and self.memory.reuseTool not in required:
            raise ValueError("Memory reuse tool must be listed in requiredTools")
        list_fields = (
            self.intentAnyOf,
            self.requiredTools,
            self.requiredSuccessfulTools,
            self.forbiddenTools,
            self.citationTermsAnyOf,
            self.retrievalStatusAnyOf,
            self.answerMustContainAnyOf,
            self.answerMustNotContain,
            self.reflectionStatusAnyOf,
            self.reflectionActionAnyOf,
        )
        if any(len(values) != len(set(values)) for values in list_fields):
            raise ValueError("evaluation expectation lists cannot contain duplicate values")
        has_assertion = any(list_fields) or any(
            value is not None
            for value in (
                self.guardrailAction,
                self.pendingActionExpected,
                self.refusalExpected,
                self.maxToolCalls,
            )
        ) or self.requireBusinessEvidence or self.requireCitations
        if not has_assertion:
            raise ValueError("evaluation case must define at least one expectation")
        return self


class EvaluationCase(BaseModel):
    id: str = Field(pattern=r"^AIM-EVAL-[A-Z_]+-\d{3}$")
    schemaVersion: Literal["AIMALL_EVAL_CASE_V1"] = EVALUATION_SCHEMA_VERSION
    datasetVersion: str = Field(pattern=r"^\d+\.\d+\.\d+$")
    enabled: bool = True
    category: EvaluationCategory
    riskLevel: EvaluationRiskLevel
    title: str = Field(min_length=1, max_length=200)
    query: str = Field(min_length=1, max_length=4000)
    fixture: EvaluationFixture = Field(default_factory=EvaluationFixture)
    expected: EvaluationExpectation
    groundTruth: list[GroundTruthFact] = Field(min_length=1)
    deterministicOnly: bool = True
    semanticRubrics: list[SemanticRubric] = Field(default_factory=list, max_length=10)
    tags: list[str] = Field(default_factory=list, max_length=20)
    source: str = Field(min_length=1, max_length=200)

    @model_validator(mode="after")
    def validate_judging_contract(self) -> "EvaluationCase":
        expected_prefix = f"AIM-EVAL-{self.category.value}-"
        if not self.id.startswith(expected_prefix):
            raise ValueError("evaluation case id category must match category")
        required_tools = set(self.expected.requiredTools)
        missing_fault_tools = {
            item.toolName for item in self.fixture.faultInjections if item.toolName not in required_tools
        }
        if missing_fault_tools:
            raise ValueError("fault injection tools must be listed in requiredTools")
        if self.deterministicOnly and self.semanticRubrics:
            raise ValueError("deterministic-only cases cannot define semanticRubrics")
        if not self.deterministicOnly and not self.semanticRubrics:
            raise ValueError("semantic evaluation cases must define semanticRubrics")
        if self.semanticRubrics:
            total = sum(item.weight for item in self.semanticRubrics)
            if abs(total - 1.0) > 0.0001:
                raise ValueError("semanticRubrics weights must sum to 1")
        return self


class EvaluationManifest(BaseModel):
    schemaVersion: Literal["AIMALL_EVAL_MANIFEST_V1"] = MANIFEST_SCHEMA_VERSION
    datasetId: str = Field(pattern=r"^[a-z0-9][a-z0-9_-]+$")
    version: str = Field(pattern=r"^\d+\.\d+\.\d+$")
    createdAt: datetime
    description: str = Field(min_length=1)
    caseFiles: list[str] = Field(min_length=1)
    expectedCaseCount: int = Field(ge=1)
    expectedCategoryCounts: dict[EvaluationCategory, int] = Field(default_factory=dict)

    @model_validator(mode="after")
    def validate_case_files(self) -> "EvaluationManifest":
        for name in self.caseFiles:
            normalized = name.replace("\\", "/")
            if normalized.startswith("/") or ".." in normalized.split("/"):
                raise ValueError("caseFiles must stay inside the dataset directory")
            if not normalized.endswith(".jsonl"):
                raise ValueError("caseFiles must use JSONL")
        if self.expectedCategoryCounts and sum(self.expectedCategoryCounts.values()) != self.expectedCaseCount:
            raise ValueError("expectedCategoryCounts must sum to expectedCaseCount")
        return self
