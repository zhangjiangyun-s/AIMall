from __future__ import annotations

import json
from datetime import datetime
from enum import StrEnum
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, Field, model_validator

from app.evaluation.loader import EvaluationDataset


class GateOperator(StrEnum):
    GTE = "GTE"
    LTE = "LTE"


class QualityGateRule(BaseModel):
    id: str = Field(pattern=r"^[a-z0-9][a-z0-9_-]+$")
    source: Literal["run", "deterministic", "rag", "specialty"]
    path: str = Field(min_length=1)
    operator: GateOperator
    threshold: float
    required: bool = True
    description: str = Field(min_length=1)


class QualityGateConfig(BaseModel):
    schemaVersion: Literal["AIMALL_QUALITY_GATE_CONFIG_V1"] = "AIMALL_QUALITY_GATE_CONFIG_V1"
    policyId: str = Field(pattern=r"^[a-z0-9][a-z0-9_-]+$")
    version: str = Field(pattern=r"^\d+\.\d+\.\d+$")
    datasetId: str
    datasetVersion: str
    profile: Literal["SMOKE", "RELEASE"] = "RELEASE"
    blockOnCurrentCriticalFailure: bool = True
    maxCaseRegressions: int = Field(default=0, ge=0)
    maxCriticalCaseRegressions: int = Field(default=0, ge=0)
    metricRegressionTolerance: float = Field(default=0.02, ge=0, le=1)
    gates: list[QualityGateRule] = Field(min_length=1)

    @model_validator(mode="after")
    def validate_gate_ids(self) -> "QualityGateConfig":
        ids = [item.id for item in self.gates]
        if len(ids) != len(set(ids)):
            raise ValueError("quality gate ids must be unique")
        return self


def load_quality_gate_config(path: str | Path) -> QualityGateConfig:
    source = Path(path)
    try:
        return QualityGateConfig.model_validate_json(source.read_text(encoding="utf-8"))
    except Exception as exc:
        raise ValueError(f"invalid quality gate config {source}: {exc}") from exc


class QualityGateEvaluator:
    def evaluate(
        self,
        *,
        dataset: EvaluationDataset,
        run: dict[str, Any],
        deterministic: dict[str, Any],
        rag: dict[str, Any],
        specialty: dict[str, Any],
        config: QualityGateConfig,
        baseline: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        self._validate_bundle(dataset, run, deterministic, rag, specialty, config)
        sources = {
            "run": self._run_metrics(run),
            "deterministic": deterministic,
            "rag": rag,
            "specialty": specialty,
        }
        gate_results = [self._evaluate_rule(rule, sources) for rule in config.gates]
        case_outcomes = self._case_outcomes(dataset, run, deterministic, rag, specialty)
        critical_failures = [
            item for item in case_outcomes if item["riskLevel"] == "CRITICAL" and item["finalPassed"] is not True
        ]
        regressions = self._regressions(config, gate_results, case_outcomes, baseline)
        blocking_reasons = []
        blocking_reasons.extend(
            f"GATE_FAILED:{item['gateId']}" for item in gate_results if item["required"] and not item["passed"]
        )
        if config.blockOnCurrentCriticalFailure:
            blocking_reasons.extend(f"CRITICAL_CASE_FAILED:{item['caseId']}" for item in critical_failures)
        if len(regressions["caseRegressions"]) > config.maxCaseRegressions:
            blocking_reasons.append("CASE_REGRESSION_LIMIT_EXCEEDED")
        if len(regressions["criticalCaseRegressions"]) > config.maxCriticalCaseRegressions:
            blocking_reasons.append("CRITICAL_CASE_REGRESSION_LIMIT_EXCEEDED")
        if regressions["metricRegressions"]:
            blocking_reasons.append("METRIC_REGRESSION_DETECTED")

        report = {
            "schemaVersion": "AIMALL_QUALITY_GATE_REPORT_V1",
            "status": "PASSED" if not blocking_reasons else "FAILED",
            "policyId": config.policyId,
            "policyVersion": config.version,
            "profile": config.profile,
            "runId": run.get("runId"),
            "datasetId": dataset.manifest.datasetId,
            "datasetVersion": dataset.manifest.version,
            "evaluatedAt": datetime.now().astimezone().isoformat(timespec="seconds"),
            "blockingReasons": blocking_reasons,
            "summary": {
                "gateCount": len(gate_results),
                "passedGates": sum(item["passed"] for item in gate_results),
                "failedGates": sum(not item["passed"] for item in gate_results),
                "caseCount": len(case_outcomes),
                "passedCases": sum(item["finalPassed"] is True for item in case_outcomes),
                "failedCases": sum(item["finalPassed"] is False for item in case_outcomes),
                "notEvaluatedCases": sum(item["finalPassed"] is None for item in case_outcomes),
                "criticalFailures": len(critical_failures),
                "caseRegressions": len(regressions["caseRegressions"]),
                "criticalCaseRegressions": len(regressions["criticalCaseRegressions"]),
                "metricRegressions": len(regressions["metricRegressions"]),
            },
            "gateResults": gate_results,
            "caseOutcomes": case_outcomes,
            "criticalFailures": critical_failures,
            "regressions": regressions,
            "metricSnapshot": {item["gateId"]: item.get("actual") for item in gate_results},
        }
        return report

    def write_report(
        self,
        report: dict[str, Any],
        json_path: str | Path,
        markdown_path: str | Path,
    ) -> None:
        output = Path(json_path)
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_suffix(output.suffix + ".tmp")
        temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.replace(output)
        Path(markdown_path).write_text(self.render_markdown(report), encoding="utf-8")

    def render_markdown(self, report: dict[str, Any]) -> str:
        lines = [
            "# AIMall Agent Quality Gate Report",
            "",
            f"- Status: **{report['status']}**",
            f"- Run ID: `{report.get('runId')}`",
            f"- Dataset: `{report.get('datasetId')}@{report.get('datasetVersion')}`",
            f"- Policy: `{report.get('policyId')}@{report.get('policyVersion')}`",
            "",
            "## Gate Results",
            "",
            "| Gate | Actual | Operator | Threshold | Result |",
            "| --- | ---: | --- | ---: | --- |",
        ]
        for item in report["gateResults"]:
            lines.append(
                f"| {item['gateId']} | {item.get('actual')} | {item['operator']} | "
                f"{item['threshold']} | {'PASS' if item['passed'] else 'FAIL'} |"
            )
        lines.extend(["", "## Failed Cases", ""])
        failed = [item for item in report["caseOutcomes"] if item["finalPassed"] is not True]
        if not failed:
            lines.append("No failed cases.")
        else:
            lines.extend(["| Case | Risk | Components | Failures |", "| --- | --- | --- | --- |"])
            for item in failed:
                components = ", ".join(f"{key}={value}" for key, value in item["components"].items())
                lines.append(
                    f"| {item['caseId']} | {item['riskLevel']} | {components} | "
                    f"{', '.join(item['failureCodes'])} |"
                )
        lines.extend(["", "## Blocking Reasons", ""])
        lines.extend(f"- `{item}`" for item in report["blockingReasons"] or ["NONE"])
        return "\n".join(lines) + "\n"

    def _validate_bundle(
        self,
        dataset: EvaluationDataset,
        run: dict[str, Any],
        deterministic: dict[str, Any],
        rag: dict[str, Any],
        specialty: dict[str, Any],
        config: QualityGateConfig,
    ) -> None:
        if config.datasetId != dataset.manifest.datasetId or config.datasetVersion != dataset.manifest.version:
            raise ValueError("quality gate policy dataset does not match loaded manifest")
        run_id = run.get("runId")
        for name, payload in (("deterministic", deterministic), ("rag", rag), ("specialty", specialty)):
            if payload.get("runId") != run_id:
                raise ValueError(f"{name} score report runId does not match raw run")
            if payload.get("datasetId") != dataset.manifest.datasetId:
                raise ValueError(f"{name} score report datasetId does not match")
            if payload.get("datasetVersion") != dataset.manifest.version:
                raise ValueError(f"{name} score report datasetVersion does not match")
        if run.get("datasetId") != dataset.manifest.datasetId or run.get("datasetVersion") != dataset.manifest.version:
            raise ValueError("raw run dataset does not match manifest")

    def _run_metrics(self, run: dict[str, Any]) -> dict[str, Any]:
        results = run.get("results") or []
        success = sum(isinstance(item, dict) and item.get("status") == "SUCCESS" for item in results)
        return {
            **run,
            "summary": {
                "totalResults": len(results),
                "executionSuccessRate": {
                    "numerator": success,
                    "denominator": len(results),
                    "value": round(success / len(results), 4) if results else None,
                },
            },
        }

    def _evaluate_rule(self, rule: QualityGateRule, sources: dict[str, dict[str, Any]]) -> dict[str, Any]:
        actual = self._get_path(sources[rule.source], rule.path)
        numeric = float(actual) if isinstance(actual, (int, float)) and not isinstance(actual, bool) else None
        passed = numeric is not None and (
            numeric >= rule.threshold if rule.operator == GateOperator.GTE else numeric <= rule.threshold
        )
        return {
            "gateId": rule.id,
            "source": rule.source,
            "path": rule.path,
            "operator": rule.operator.value,
            "threshold": rule.threshold,
            "actual": actual,
            "required": rule.required,
            "passed": passed if rule.required else passed or actual is None,
            "description": rule.description,
            "failureReason": None if passed else "MISSING_METRIC" if numeric is None else "THRESHOLD_NOT_MET",
        }

    def _case_outcomes(
        self,
        dataset: EvaluationDataset,
        run: dict[str, Any],
        deterministic: dict[str, Any],
        rag: dict[str, Any],
        specialty: dict[str, Any],
    ) -> list[dict[str, Any]]:
        run_by_id = {item["caseId"]: item for item in run.get("results") or [] if isinstance(item, dict)}
        deterministic_by_id = {item["caseId"]: item for item in deterministic.get("caseScores") or []}
        rag_by_id = {item["caseId"]: item for item in rag.get("caseScores") or []}
        specialty_by_id = {item["caseId"]: item for item in specialty.get("caseScores") or []}
        specialty_categories = {"GUARDRAIL", "PERMISSION", "HITL", "MEMORY", "TOOL_FAILURE", "REFLECTION"}
        outcomes = []
        for case in dataset.cases:
            raw = run_by_id.get(case.id)
            deterministic_score = deterministic_by_id.get(case.id)
            components: dict[str, bool | None] = {
                "execution": raw is not None and raw.get("status") == "SUCCESS" if raw else None,
                "deterministic": deterministic_score.get("deterministicPassed") if deterministic_score else None,
            }
            if case.expected.rag is not None:
                components["rag"] = rag_by_id.get(case.id, {}).get("passed")
            if case.category.value in specialty_categories:
                components["specialty"] = specialty_by_id.get(case.id, {}).get("passed")
            values = list(components.values())
            final_passed = None if any(value is None for value in values) else all(values)
            failure_codes = []
            for score in (deterministic_score, rag_by_id.get(case.id), specialty_by_id.get(case.id)):
                if isinstance(score, dict):
                    failure_codes.extend(str(item) for item in score.get("failureCodes") or [])
            if raw is None:
                failure_codes.append("MISSING_RUN_RESULT")
            outcomes.append(
                {
                    "caseId": case.id,
                    "category": case.category.value,
                    "riskLevel": case.riskLevel.value,
                    "components": components,
                    "finalPassed": final_passed,
                    "failureCodes": sorted(set(failure_codes)),
                }
            )
        return outcomes

    def _regressions(
        self,
        config: QualityGateConfig,
        gates: list[dict[str, Any]],
        outcomes: list[dict[str, Any]],
        baseline: dict[str, Any] | None,
    ) -> dict[str, Any]:
        if not baseline:
            return {"baselineRunId": None, "caseRegressions": [], "criticalCaseRegressions": [], "metricRegressions": []}
        if baseline.get("datasetId") != config.datasetId:
            raise ValueError("baseline quality report datasetId does not match")
        baseline_cases = {item["caseId"]: item for item in baseline.get("caseOutcomes") or []}
        case_regressions = [
            {"caseId": item["caseId"], "riskLevel": item["riskLevel"]}
            for item in outcomes
            if baseline_cases.get(item["caseId"], {}).get("finalPassed") is True and item["finalPassed"] is not True
        ]
        baseline_metrics = baseline.get("metricSnapshot") or {}
        current_rules = {item.id: item for item in config.gates}
        metric_regressions = []
        for gate in gates:
            previous = baseline_metrics.get(gate["gateId"])
            current = gate.get("actual")
            rule = current_rules[gate["gateId"]]
            if not isinstance(previous, (int, float)) or not isinstance(current, (int, float)):
                continue
            delta = current - previous
            regressed = (
                delta < -config.metricRegressionTolerance
                if rule.operator == GateOperator.GTE
                else delta > config.metricRegressionTolerance
            )
            if regressed:
                metric_regressions.append(
                    {"gateId": gate["gateId"], "baseline": previous, "current": current, "delta": round(delta, 4)}
                )
        return {
            "baselineRunId": baseline.get("runId"),
            "caseRegressions": case_regressions,
            "criticalCaseRegressions": [item for item in case_regressions if item["riskLevel"] == "CRITICAL"],
            "metricRegressions": metric_regressions,
        }

    def _get_path(self, payload: dict[str, Any], path: str) -> Any:
        value: Any = payload
        for part in path.split("."):
            if not isinstance(value, dict) or part not in value:
                return None
            value = value[part]
        return value


quality_gate_evaluator = QualityGateEvaluator()
