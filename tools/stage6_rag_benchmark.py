"""Aggregate three independent scored RAG runs per mode and apply release gates."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "aimall-ai-service"))

from app.evaluation.stage6_rag_benchmark import CANONICAL_MODES, aggregate_all_modes  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--run",
        action="append",
        default=[],
        metavar="MODE=RAG_SCORED_JSON",
        help="Repeat at least three times for each of DOC_ONLY, HYBRID and VECTOR.",
    )
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    reports: dict[str, list[dict]] = {mode: [] for mode in CANONICAL_MODES}
    try:
        for item in args.run:
            mode, separator, raw_path = item.partition("=")
            mode = mode.strip().upper()
            if not separator or mode not in reports:
                raise ValueError(f"Invalid --run value: {item}")
            reports[mode].append(json.loads(Path(raw_path).read_text(encoding="utf-8")))
        result = aggregate_all_modes(reports)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(json.dumps({"status": "ERROR", "error": str(exc)}, ensure_ascii=False, indent=2))
        return 2

    print(json.dumps({"passed": result["passed"], "output": str(args.output.resolve())}, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
