"""Evaluate AIMall release quality gates from one raw run and its score reports."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AI_ROOT = ROOT / "aimall-ai-service"
sys.path.insert(0, str(AI_ROOT))

from app.evaluation import (  # noqa: E402
    load_evaluation_dataset,
    load_quality_gate_config,
    quality_gate_evaluator,
)


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--deterministic", type=Path, required=True)
    parser.add_argument("--rag", type=Path, required=True)
    parser.add_argument("--specialty", type=Path, required=True)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=AI_ROOT / "data" / "evaluation" / "manifest.json",
    )
    parser.add_argument(
        "--config",
        type=Path,
        default=AI_ROOT / "data" / "evaluation" / "quality-gates-v1.json",
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--markdown", type=Path)
    args = parser.parse_args()
    markdown = args.markdown or args.output.with_suffix(".md")
    try:
        report = quality_gate_evaluator.evaluate(
            dataset=load_evaluation_dataset(args.manifest),
            run=read_json(args.run),
            deterministic=read_json(args.deterministic),
            rag=read_json(args.rag),
            specialty=read_json(args.specialty),
            config=load_quality_gate_config(args.config),
            baseline=read_json(args.baseline) if args.baseline else None,
        )
        quality_gate_evaluator.write_report(report, args.output, markdown)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(json.dumps({"status": "ERROR", "error": str(exc)}, ensure_ascii=False, indent=2))
        return 2
    print(
        json.dumps(
            {
                "status": report["status"],
                "runId": report["runId"],
                "summary": report["summary"],
                "blockingReasons": report["blockingReasons"],
                "json": str(args.output.resolve()),
                "markdown": str(markdown.resolve()),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
