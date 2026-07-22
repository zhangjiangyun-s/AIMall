from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from app.config.settings import normalize_rag_retrieval_mode


DEFAULT_CONFIG_PATH = Path(__file__).resolve().parents[2] / "data" / "evaluation" / "rag-mode-quality-gates-v1.json"


class RagModeQualityGate:
    def __init__(self, config_path: str | Path = DEFAULT_CONFIG_PATH):
        payload = json.loads(Path(config_path).read_text(encoding="utf-8"))
        self.version = str(payload["version"])
        self.thresholds = dict(payload["modes"])

    def evaluate(self, retrieval_mode: str, summary: dict[str, Any]) -> dict[str, Any]:
        mode = normalize_rag_retrieval_mode(retrieval_mode)
        threshold = self.thresholds.get(mode)
        if not isinstance(threshold, dict):
            raise ValueError(f"RAG quality gate is not configured for mode {mode}")

        metrics = {
            "recallAtK": self._value(summary.get("recallAtK")),
            "mrr": self._value(summary.get("mrr")),
            "citationAccuracy": self._value(summary.get("citationAccuracy")),
            "hallucinationRate": 1.0 - self._value(summary.get("citationFaithfulness")),
        }
        checks = [
            self._minimum_check("recallAtK", metrics, threshold),
            self._minimum_check("mrr", metrics, threshold),
            self._minimum_check("citationAccuracy", metrics, threshold),
            {
                "metric": "hallucinationRate",
                "operator": "<=",
                "threshold": float(threshold["maxHallucinationRate"]),
                "actual": round(metrics["hallucinationRate"], 4),
                "passed": metrics["hallucinationRate"] <= float(threshold["maxHallucinationRate"]),
            },
        ]
        return {
            "schemaVersion": "AIMALL_RAG_MODE_QUALITY_GATE_V1",
            "configVersion": self.version,
            "retrievalMode": mode,
            "passed": all(item["passed"] for item in checks),
            "checks": checks,
        }

    def _minimum_check(
        self,
        metric: str,
        metrics: dict[str, float],
        threshold: dict[str, Any],
    ) -> dict[str, Any]:
        threshold_key = f"min{metric[0].upper()}{metric[1:]}"
        expected = float(threshold[threshold_key])
        actual = metrics[metric]
        return {
            "metric": metric,
            "operator": ">=",
            "threshold": expected,
            "actual": round(actual, 4),
            "passed": actual >= expected,
        }

    def _value(self, metric: Any) -> float:
        if isinstance(metric, dict):
            metric = metric.get("value")
        return float(metric) if isinstance(metric, (int, float)) else 0.0


rag_mode_quality_gate = RagModeQualityGate()
