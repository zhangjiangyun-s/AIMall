"""Execute AIMall evaluation cases and persist sanitized raw SSE results."""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AI_ROOT = ROOT / "aimall-ai-service"
sys.path.insert(0, str(AI_ROOT))

from app.evaluation import (  # noqa: E402
    EvaluationRunner,
    EvaluationRunnerConfig,
    load_evaluation_dataset,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=AI_ROOT / "data" / "evaluation" / "manifest.json",
    )
    parser.add_argument("--base-url", default=os.getenv("AIMALL_BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--password", default=os.getenv("PHASE11_TEST_PASSWORD", "Phase11Test!2026"))
    parser.add_argument("--timeout", type=float, default=240)
    parser.add_argument("--concurrency", type=int, default=2)
    parser.add_argument("--case-id", action="append", default=[])
    parser.add_argument("--category", action="append", default=[])
    parser.add_argument("--limit", type=int)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--disable-fault-harness", action="store_true")
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


async def async_main() -> int:
    args = parse_args()
    dataset = load_evaluation_dataset(args.manifest)
    output = args.output or (
        ROOT
        / ".acceptance"
        / "evaluation"
        / f"{dataset.manifest.datasetId}-{dataset.manifest.version}-{datetime.now():%Y%m%d-%H%M%S}.json"
    )
    runner = EvaluationRunner(
        EvaluationRunnerConfig(
            base_url=args.base_url,
            password=args.password,
            timeout_seconds=args.timeout,
            concurrency=args.concurrency,
            output_path=output,
            resume=args.resume,
            enable_fault_harness=not args.disable_fault_harness,
        )
    )
    report = await runner.run(
        dataset,
        case_ids=set(args.case_id) or None,
        categories={item.upper() for item in args.category} or None,
        limit=args.limit,
    )
    print(
        json.dumps(
            {
                "runId": report["runId"],
                "datasetVersion": report["datasetVersion"],
                "selected": report["selectedCaseCount"],
                "completed": report["completedCaseCount"],
                "counts": report["counts"],
                "output": str(output.resolve()),
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 1 if report["counts"]["ERROR"] or report["counts"]["TIMEOUT"] else 0


def main() -> int:
    try:
        return asyncio.run(async_main())
    except (ValueError, OSError) as exc:
        print(json.dumps({"status": "ERROR", "error": str(exc)}, ensure_ascii=False, indent=2))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
