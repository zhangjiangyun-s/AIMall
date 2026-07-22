from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from app.config.settings import settings


class ModelPurpose(StrEnum):
    PLANNING = "PLANNING"
    SUMMARY = "SUMMARY"
    GENERATION = "GENERATION"
    JUDGE = "JUDGE"


@dataclass(frozen=True)
class ModelCandidate:
    model: str
    input_cost_per_million_usd: float
    output_cost_per_million_usd: float
    role: str


@dataclass(frozen=True)
class ModelRoute:
    purpose: ModelPurpose
    selected_model: str
    candidates: tuple[ModelCandidate, ...]

    @property
    def attempted_models(self) -> list[str]:
        return [candidate.model for candidate in self.candidates]


class ModelRouter:
    """Selects a bounded, de-duplicated model fallback chain for each task type."""

    _FAST_PURPOSES = {ModelPurpose.SUMMARY, ModelPurpose.JUDGE}

    def route(self, purpose: ModelPurpose | str | None) -> ModelRoute:
        normalized = self._normalize_purpose(purpose)
        if not settings.MODEL_ROUTING_ENABLED:
            legacy = ModelCandidate(
                model=settings.AGNES_MODEL.strip(),
                input_cost_per_million_usd=settings.AGNES_INPUT_COST_PER_MILLION_USD,
                output_cost_per_million_usd=settings.AGNES_OUTPUT_COST_PER_MILLION_USD,
                role="LEGACY",
            )
            return ModelRoute(purpose=normalized, selected_model=legacy.model, candidates=(legacy,))
        primary = self._fast_candidate() if normalized in self._FAST_PURPOSES else self._primary_candidate()
        candidates = [primary]
        fallback = self._fallback_candidate()
        if fallback and fallback.model != primary.model:
            candidates.append(fallback)
        return ModelRoute(
            purpose=normalized,
            selected_model=primary.model,
            candidates=tuple(candidates),
        )

    def _fast_candidate(self) -> ModelCandidate:
        return ModelCandidate(
            model=(settings.AGNES_FAST_MODEL or settings.AGNES_MODEL).strip(),
            input_cost_per_million_usd=settings.AGNES_FAST_INPUT_COST_PER_MILLION_USD,
            output_cost_per_million_usd=settings.AGNES_FAST_OUTPUT_COST_PER_MILLION_USD,
            role="FAST",
        )

    def _primary_candidate(self) -> ModelCandidate:
        return ModelCandidate(
            model=(settings.AGNES_PRIMARY_MODEL or settings.AGNES_MODEL).strip(),
            input_cost_per_million_usd=settings.AGNES_PRIMARY_INPUT_COST_PER_MILLION_USD,
            output_cost_per_million_usd=settings.AGNES_PRIMARY_OUTPUT_COST_PER_MILLION_USD,
            role="PRIMARY",
        )

    def _fallback_candidate(self) -> ModelCandidate | None:
        model = settings.AGNES_FALLBACK_MODEL.strip()
        if not model:
            return None
        return ModelCandidate(
            model=model,
            input_cost_per_million_usd=settings.AGNES_FALLBACK_INPUT_COST_PER_MILLION_USD,
            output_cost_per_million_usd=settings.AGNES_FALLBACK_OUTPUT_COST_PER_MILLION_USD,
            role="FALLBACK",
        )

    @staticmethod
    def _normalize_purpose(value: ModelPurpose | str | None) -> ModelPurpose:
        try:
            return ModelPurpose(str(value or ModelPurpose.GENERATION).upper())
        except ValueError:
            return ModelPurpose.GENERATION


model_router = ModelRouter()
