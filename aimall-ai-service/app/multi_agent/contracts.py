from __future__ import annotations

from enum import StrEnum

from pydantic import BaseModel, Field, model_validator


class SpecialistId(StrEnum):
    PRODUCT = "PRODUCT_SPECIALIST"
    ORDER = "ORDER_SPECIALIST"
    POLICY = "POLICY_SPECIALIST"


class DelegationStatus(StrEnum):
    PLANNED = "PLANNED"
    SKIPPED = "SKIPPED"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class Delegation(BaseModel):
    specialist: SpecialistId
    sequence: int = Field(ge=1, le=3)
    reason: str = Field(min_length=1, max_length=240)
    allowedTools: list[str] = Field(min_length=1, max_length=10)
    status: DelegationStatus = DelegationStatus.PLANNED
    duplicateCallsPrevented: int = Field(default=0, ge=0, le=6)

    @model_validator(mode="after")
    def validate_tools(self) -> "Delegation":
        if len(self.allowedTools) != len(set(self.allowedTools)):
            raise ValueError("delegation allowedTools must be unique")
        return self


class DelegationPlan(BaseModel):
    strategy: str = "supervisor_rule_v1"
    intent: str = Field(min_length=1, max_length=64)
    delegations: list[Delegation] = Field(default_factory=list, max_length=3)
    fallbackToLegacy: bool = False
    fallbackReason: str | None = Field(default=None, max_length=240)

    @model_validator(mode="after")
    def validate_sequence(self) -> "DelegationPlan":
        sequences = [item.sequence for item in self.delegations]
        if sequences != list(range(1, len(sequences) + 1)):
            raise ValueError("delegation sequences must start at one and be contiguous")
        specialists = [item.specialist for item in self.delegations]
        if len(specialists) != len(set(specialists)):
            raise ValueError("a specialist may be delegated at most once per request")
        if self.fallbackToLegacy and self.delegations:
            raise ValueError("legacy fallback plans cannot contain delegations")
        return self
