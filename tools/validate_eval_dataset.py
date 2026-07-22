"""Validate an AIMall versioned evaluation dataset without executing Agent cases."""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AI_ROOT = ROOT / "aimall-ai-service"
sys.path.insert(0, str(AI_ROOT))

from app.evaluation import load_evaluation_dataset  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "manifest",
        nargs="?",
        type=Path,
        default=AI_ROOT / "data" / "evaluation" / "manifest.json",
    )
    args = parser.parse_args()
    try:
        dataset = load_evaluation_dataset(args.manifest)
    except ValueError as exc:
        print(json.dumps({"valid": False, "error": str(exc)}, ensure_ascii=False, indent=2))
        return 1

    categories = Counter(case.category.value for case in dataset.cases)
    payload = {
        "valid": True,
        "datasetId": dataset.manifest.datasetId,
        "version": dataset.manifest.version,
        "caseCount": len(dataset.cases),
        "enabledCaseCount": sum(1 for case in dataset.cases if case.enabled),
        "deterministicCaseCount": sum(1 for case in dataset.cases if case.deterministicOnly),
        "semanticCaseCount": sum(1 for case in dataset.cases if not case.deterministicOnly),
        "categories": dict(sorted(categories.items())),
    }
    print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
