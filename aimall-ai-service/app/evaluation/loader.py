from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

from pydantic import BaseModel, ValidationError

from app.evaluation.models import EvaluationCase, EvaluationManifest


class EvaluationDataset(BaseModel):
    manifest: EvaluationManifest
    cases: list[EvaluationCase]


def load_evaluation_dataset(manifest_path: str | Path) -> EvaluationDataset:
    path = Path(manifest_path).resolve()
    try:
        manifest = EvaluationManifest.model_validate_json(path.read_text(encoding="utf-8"))
    except (OSError, ValidationError, ValueError) as exc:
        raise ValueError(f"invalid evaluation manifest {path}: {exc}") from exc

    cases: list[EvaluationCase] = []
    ids: set[str] = set()
    for relative_name in manifest.caseFiles:
        case_path = (path.parent / relative_name).resolve()
        if path.parent not in case_path.parents:
            raise ValueError(f"evaluation case file escapes dataset directory: {relative_name}")
        try:
            lines = case_path.read_text(encoding="utf-8").splitlines()
        except OSError as exc:
            raise ValueError(f"cannot read evaluation case file {case_path}: {exc}") from exc
        for line_number, line in enumerate(lines, start=1):
            if not line.strip():
                continue
            try:
                case = EvaluationCase.model_validate_json(line)
            except (ValidationError, ValueError) as exc:
                raise ValueError(f"invalid evaluation case {case_path}:{line_number}: {exc}") from exc
            if case.datasetVersion != manifest.version:
                raise ValueError(
                    f"dataset version mismatch at {case_path}:{line_number}: "
                    f"{case.datasetVersion} != {manifest.version}"
                )
            if case.id in ids:
                raise ValueError(f"duplicate evaluation case id: {case.id}")
            ids.add(case.id)
            cases.append(case)

    if len(cases) != manifest.expectedCaseCount:
        raise ValueError(
            f"evaluation case count mismatch: {len(cases)} != {manifest.expectedCaseCount}"
        )
    actual_counts = Counter(item.category for item in cases)
    for category, expected_count in manifest.expectedCategoryCounts.items():
        if actual_counts[category] != expected_count:
            raise ValueError(
                f"evaluation category count mismatch for {category.value}: "
                f"{actual_counts[category]} != {expected_count}"
            )
    return EvaluationDataset(manifest=manifest, cases=cases)
