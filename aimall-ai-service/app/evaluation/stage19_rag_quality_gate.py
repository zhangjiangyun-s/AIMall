from __future__ import annotations

import json
import hashlib
import re
from pathlib import Path
from typing import Any

from app.evaluation.loader import load_evaluation_dataset


DEFAULT_MANIFEST = Path(__file__).resolve().parents[2] / "data" / "evaluation" / "evalset-v1-manifest.json"
LOWER_IS_WORSE = ("policyQaRecallAt5", "citationPrecision", "noAnswerPrecision")
HIGHER_IS_WORSE = ("unsupportedAnswerRate", "tenantLeakage")
THRESHOLDS = {
    "policyQaRecallAt5": (">=", 0.90),
    "citationPrecision": (">=", 0.95),
    "unsupportedAnswerRate": ("<=", 0.01),
    "noAnswerPrecision": (">=", 0.90),
    "tenantLeakage": ("==", 0.0),
}


class Stage19RagQualityGate:
    def __init__(self, manifest_path: str | Path = DEFAULT_MANIFEST):
        self.manifest = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
        self.version = str(self.manifest["evaluationSetVersion"])
        self.categories = set(self.manifest["categories"])
        self.release_seeds = [int(value) for value in self.manifest.get("releaseSeeds") or []]
        if not re.fullmatch(r"evalset-v\d+", self.version):
            raise ValueError("evaluation set version must match evalset-vN")
        if self.manifest.get("sourceKind") != "OFFLINE_CURATED" or self.manifest.get("containsProductionUserData"):
            raise ValueError("release evaluation set must be offline curated and contain no production user data")
        if len(self.release_seeds) != 3 or len(set(self.release_seeds)) != 3:
            raise ValueError("evaluation manifest must define exactly 3 unique release seeds")
        dataset = load_evaluation_dataset(manifest_path)
        case_files = self.manifest.get("caseFiles") or []
        if len(case_files) != 1:
            raise ValueError("Stage 19 evaluation set must use one immutable case file")
        case_path = Path(manifest_path).parent / str(case_files[0])
        actual_hash = hashlib.sha256(case_path.read_bytes()).hexdigest()
        if actual_hash != self.manifest.get("caseFileSha256"):
            raise ValueError("evaluation case file hash does not match the fixed manifest")
        actual_categories = {case.category.value for case in dataset.cases}
        if actual_categories != self.categories:
            raise ValueError("evaluation case categories do not match the Stage 19 manifest")
        self.case_count = len(dataset.cases)

    def evaluate(self, reports: list[dict[str, Any]]) -> dict[str, Any]:
        if len(reports) != 3:
            raise ValueError("RAG release gate requires exactly 3 independent runs")
        run_ids = [str(report.get("runId") or "").strip() for report in reports]
        if any(not value for value in run_ids) or len(set(run_ids)) != len(run_ids):
            raise ValueError("RAG release gate requires unique non-empty runId values")
        random_seeds = [int(report.get("randomSeed") or 0) for report in reports]
        if sorted(random_seeds) != sorted(self.release_seeds):
            raise ValueError("RAG release runs must use the fixed manifest releaseSeeds")
        for report in reports:
            self._validate_report(report)
        if len({str(report["publicationVersion"]) for report in reports}) != 1:
            raise ValueError("all runs must evaluate one publicationVersion")
        if len({int(report["retrievalEpoch"]) for report in reports}) != 1:
            raise ValueError("all runs must evaluate one retrievalEpoch")

        worst: dict[str, dict[str, Any]] = {}
        for metric in LOWER_IS_WORSE:
            values = [self._metric(report, metric) for report in reports]
            worst[metric] = {"count": min(value[0] for value in values), "value": min(value[1] for value in values)}
        for metric in HIGHER_IS_WORSE:
            values = [self._metric(report, metric) for report in reports]
            worst[metric] = {"count": min(value[0] for value in values), "value": max(value[1] for value in values)}

        checks = [self._check(metric, worst[metric]["value"]) for metric in THRESHOLDS]
        return {
            "schemaVersion": "AIMALL_STAGE19_RAG_GATE_V1",
            "evaluationSetVersion": self.version,
            "evaluationCaseCount": self.case_count,
            "runCount": len(reports),
            "runIds": run_ids,
            "randomSeeds": random_seeds,
            "publicationVersion": str(reports[0]["publicationVersion"]),
            "retrievalEpoch": int(reports[0]["retrievalEpoch"]),
            "worstCaseSummary": worst,
            "checks": checks,
            "passed": all(check["passed"] for check in checks),
        }

    def _validate_report(self, report: dict[str, Any]) -> None:
        if report.get("evaluationSetVersion") != self.version:
            raise ValueError("all runs must use the fixed evaluationSetVersion")
        if report.get("sourceKind") != "OFFLINE_CURATED" or report.get("containsProductionUserData") is not False:
            raise ValueError("online user samples cannot be used as the offline release gate dataset")
        if set(report.get("categories") or []) != self.categories:
            raise ValueError("each run must cover every category in the fixed evaluation manifest")
        if not str(report.get("publicationVersion") or "").strip() or int(report.get("retrievalEpoch") or 0) <= 0:
            raise ValueError("publicationVersion and retrievalEpoch are required")
    def _metric(self, report: dict[str, Any], metric: str) -> tuple[int, float]:
        value = (report.get("summary") or {}).get(metric)
        if not isinstance(value, dict) or int(value.get("count") or 0) <= 0:
            raise ValueError(f"missing evaluated metric {metric}")
        numeric = value.get("value")
        if not isinstance(numeric, (int, float)):
            raise ValueError(f"metric {metric} must be numeric")
        return int(value["count"]), float(numeric)

    def _check(self, metric: str, actual: float) -> dict[str, Any]:
        operator, threshold = THRESHOLDS[metric]
        passed = actual >= threshold if operator == ">=" else actual <= threshold if operator == "<=" else actual == threshold
        return {"metric": metric, "operator": operator, "threshold": threshold, "actual": round(actual, 6), "passed": passed}


stage19_rag_quality_gate = Stage19RagQualityGate()
