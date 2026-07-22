"""Score Guardrail, permission, HITL, Memory, Tool Failure, and Reflection cases."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AI_ROOT = ROOT / "aimall-ai-service"
sys.path.insert(0, str(AI_ROOT))

from app.evaluation import load_evaluation_dataset, specialty_evaluation_scorer  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run", type=Path)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=AI_ROOT / "data" / "evaluation" / "manifest.json",
    )
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = args.output or args.run.with_suffix(".specialty-scored.json")
    try:
        dataset = load_evaluation_dataset(args.manifest)
        report = specialty_evaluation_scorer.score_file(dataset, args.run, output)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(json.dumps({"status": "ERROR", "error": str(exc)}, ensure_ascii=False, indent=2))
        return 1
    print(
        json.dumps(
            {
                "runId": report["runId"],
                "datasetVersion": report["datasetVersion"],
                "summary": report["summary"],
                "output": str(output.resolve()),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
