"""Run and verify an explicit rebuild of current business knowledge versions in Milvus."""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "aimall-ai-service"))

from app.evaluation.stage6_milvus_rebuild import verify_business_rebuild  # noqa: E402
from app.rag.milvus_store import milvus_store  # noqa: E402
from app.tools.java_client import java_client  # noqa: E402


async def async_main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true", help="Required because this creates rebuild tasks.")
    parser.add_argument("--allow-empty", action="store_true")
    parser.add_argument("--timeout", type=float, default=900)
    parser.add_argument(
        "--output",
        type=Path,
        default=ROOT / ".acceptance" / "stage6" / "milvus-business-rebuild.json",
    )
    args = parser.parse_args()
    if not args.execute:
        print(json.dumps({"status": "BLOCKED", "reason": "Pass --execute to create rebuild tasks"}, indent=2))
        return 2

    try:
        report = await verify_business_rebuild(
            java_client,
            milvus_store,
            timeout_seconds=args.timeout,
            allow_empty=args.allow_empty,
        )
        report["executedAt"] = datetime.now(timezone.utc).isoformat()
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    except Exception as exc:
        print(json.dumps({"status": "ERROR", "error": str(exc)}, ensure_ascii=False, indent=2))
        return 1
    finally:
        await java_client.close()

    print(json.dumps({"passed": report["passed"], "output": str(args.output.resolve())}, indent=2))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(async_main()))
