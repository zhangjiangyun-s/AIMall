from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import math
import os
import platform
import shutil
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    return round(ordered[max(0, math.ceil(len(ordered) * ratio) - 1)], 3)


def source_revision(root: Path) -> str:
    git_dir = root / ".git"
    try:
        if git_dir.is_file():
            git_dir = (root / git_dir.read_text(encoding="utf-8").split(":", 1)[1].strip()).resolve()
        head = (git_dir / "HEAD").read_text(encoding="utf-8").strip()
        if head.startswith("ref: "):
            revision = (git_dir / head[5:]).read_text(encoding="utf-8").strip()
            if revision:
                return revision
        elif head:
            return head
    except Exception:
        pass
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=root, text=True, stderr=subprocess.DEVNULL, timeout=5
        ).strip()
    except Exception:
        return "unavailable"


def hardware_snapshot(root: Path) -> dict[str, Any]:
    disk = shutil.disk_usage(root)
    return {
        "os": platform.platform(),
        "python": platform.python_version(),
        "cpuLogical": os.cpu_count(),
        "machine": platform.machine(),
        "diskFreeBytes": disk.free,
    }


def resolve_request(scenario: dict[str, Any], java_base: str, ai_base: str) -> tuple[str, bytes | None, dict[str, str]]:
    base = java_base if scenario.get("base") == "java" else ai_base
    url = base.rstrip("/") + str(scenario["path"])
    body = os.getenv(str(scenario.get("bodyEnv") or "")) if scenario.get("bodyEnv") else None
    headers = {"Content-Type": str(scenario.get("contentType") or "application/json")}
    for name, env_name in dict(scenario.get("headerEnv") or {}).items():
        value = os.getenv(str(env_name))
        if value:
            headers[str(name)] = value
    return url, body.encode("utf-8") if body is not None else None, headers


def issue_request(
    url: str,
    method: str,
    body: bytes | None,
    headers: dict[str, str],
    timeout: float,
    expected: set[int],
) -> dict[str, Any]:
    started = time.perf_counter()
    status = 0
    error = None
    try:
        request = urllib.request.Request(url, data=body, headers=headers, method=method)
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = int(response.status)
            response.read(16_384)
    except urllib.error.HTTPError as exc:
        status = int(exc.code)
        error = f"HTTP_{status}"
    except Exception as exc:
        error = type(exc).__name__
    latency_ms = (time.perf_counter() - started) * 1000
    return {"status": status, "latencyMs": round(latency_ms, 3), "ok": status in expected, "error": error}


def run_http_scenario(scenario: dict[str, Any], args: argparse.Namespace) -> dict[str, Any]:
    missing = [name for name in scenario.get("requiredEnv", []) if not os.getenv(str(name))]
    if missing:
        return {"executed": False, "passed": False, "blockers": [f"missing environment variable: {name}" for name in missing]}
    if scenario.get("sideEffect") and not args.approve_write_scenarios:
        return {"executed": False, "passed": False, "blockers": ["write scenario requires --approve-write-scenarios"]}

    url, body, headers = resolve_request(scenario, args.java_base_url, args.ai_base_url)
    duration = max(0.1, float(args.duration_seconds or scenario.get("durationSeconds") or 1))
    target_rps = float(scenario.get("targetRps") or 0)
    concurrency = max(1, int(scenario.get("concurrency") or 1))
    if scenario.get("kind") == "SSE":
        request_count = int(scenario.get("targetConnections") or concurrency)
    else:
        request_count = max(1, math.ceil(target_rps * duration))
    if args.max_requests:
        request_count = min(request_count, args.max_requests)
    expected = {int(value) for value in scenario.get("expectedStatuses", [200])}
    timeout = float(scenario.get("timeoutSeconds") or 30)
    interval = (1 / target_rps) if target_rps > 0 and scenario.get("kind") != "SSE" else 0

    warmup_seconds = max(0.0, float(
        args.warmup_seconds if args.warmup_seconds is not None else scenario.get("warmupSeconds") or 0
    ))
    if scenario.get("kind") == "SSE":
        warmup_count = concurrency if warmup_seconds > 0 else 0
    else:
        warmup_count = math.ceil(target_rps * warmup_seconds)
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        warmup_futures = []
        warmup_start = time.perf_counter()
        for index in range(warmup_count):
            target_time = warmup_start + index * interval
            delay = target_time - time.perf_counter()
            if delay > 0:
                time.sleep(delay)
            request_body = body.replace(b"{{requestId}}", uuid.uuid4().hex.encode("ascii")) if body else None
            warmup_futures.append(executor.submit(
                issue_request, url, str(scenario.get("method") or "GET"), request_body, headers, timeout, expected
            ))
        for future in concurrent.futures.as_completed(warmup_futures):
            future.result()

    results: list[dict[str, Any]] = []
    wall_start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = []
        for index in range(request_count):
            target_time = wall_start + index * interval
            delay = target_time - time.perf_counter()
            if delay > 0:
                time.sleep(delay)
            request_body = body.replace(b"{{requestId}}", uuid.uuid4().hex.encode("ascii")) if body else None
            futures.append(executor.submit(
                issue_request, url, str(scenario.get("method") or "GET"), request_body, headers, timeout, expected
            ))
        for future in concurrent.futures.as_completed(futures):
            results.append(future.result())
    elapsed = time.perf_counter() - wall_start

    latencies = [float(item["latencyMs"]) for item in results]
    failures = [item for item in results if not item["ok"]]
    error_rate = round(len(failures) / len(results), 6) if results else 1.0
    p95 = percentile(latencies, 0.95)
    p99 = percentile(latencies, 0.99)
    checks = {
        "errorRate": error_rate <= float(scenario.get("errorRateMax", 0)),
        "p95": "p95Ms" not in scenario or p95 <= float(scenario["p95Ms"]),
        "p99": "p99Ms" not in scenario or p99 <= float(scenario["p99Ms"]),
        "requestCount": len(results) == request_count,
    }
    return {
        "executed": True,
        "passed": all(checks.values()),
        "url": url,
        "requestCount": len(results),
        "warmupRequestCount": warmup_count,
        "successCount": len(results) - len(failures),
        "failureCount": len(failures),
        "errorRate": error_rate,
        "latencyMs": {
            "min": round(min(latencies), 3) if latencies else 0,
            "avg": round(sum(latencies) / len(latencies), 3) if latencies else 0,
            "p95": p95,
            "p99": p99,
            "max": round(max(latencies), 3) if latencies else 0,
        },
        "achievedRps": round(len(results) / elapsed, 3) if elapsed else 0,
        "elapsedSeconds": round(elapsed, 3),
        "checks": checks,
        "failureClasses": sorted({str(item.get("error") or item.get("status")) for item in failures}),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="AIMall Stage 21 capacity runner")
    parser.add_argument("--manifest", default="docs/operations/capacity-gates.json")
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--java-base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--ai-base-url", default="http://127.0.0.1:8000")
    parser.add_argument("--duration-seconds", type=float)
    parser.add_argument("--warmup-seconds", type=float)
    parser.add_argument("--max-requests", type=int)
    parser.add_argument("--approve-write-scenarios", action="store_true")
    parser.add_argument("--data-volume", default="not-recorded")
    parser.add_argument("--model", default=os.getenv("AGNES_MODEL", "not-recorded"))
    parser.add_argument("--cache-hit-rate", default="not-recorded")
    args = parser.parse_args()

    root = Path(__file__).resolve().parents[1]
    manifest_path = Path(args.manifest)
    if not manifest_path.is_absolute():
        manifest_path = root / manifest_path
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    scenario = next((item for item in manifest["scenarios"] if item["id"] == args.scenario), None)
    if scenario is None:
        raise SystemExit(f"unknown scenario: {args.scenario}")
    if scenario.get("kind") == "METRIC":
        raise SystemExit("metric scenarios are evaluated by the Stage 21 quality gate")

    started_at = datetime.now(timezone.utc)
    result = run_http_scenario(scenario, args)
    revision = source_revision(root)
    metadata_blockers = []
    if revision == "unavailable":
        metadata_blockers.append("source revision is unavailable")
    for name, value in {
        "dataVolume": args.data_volume,
        "model": args.model,
        "cacheHitRate": args.cache_hit_rate,
    }.items():
        if not str(value).strip() or str(value).strip() == "not-recorded":
            metadata_blockers.append(f"{name} is not recorded")
    evidence = {
        "schemaVersion": "AIMALL_CAPACITY_RUN_V1",
        "runId": "S21-" + started_at.strftime("%Y%m%d%H%M%S") + "-" + uuid.uuid4().hex[:8],
        "startedAt": started_at.isoformat(),
        "finishedAt": datetime.now(timezone.utc).isoformat(),
        "sourceRevision": revision,
        "scenario": args.scenario,
        "hardware": hardware_snapshot(root),
        "dataVolume": args.data_volume,
        "model": args.model,
        "cacheHitRate": args.cache_hit_rate,
        "concurrencyModel": {
            "kind": scenario.get("kind"),
            "targetRps": scenario.get("targetRps"),
            "targetConnections": scenario.get("targetConnections"),
            "workers": scenario.get("concurrency"),
            "durationSeconds": args.duration_seconds or scenario.get("durationSeconds"),
        },
        "thresholds": {
            key: scenario[key] for key in ("p95Ms", "p99Ms", "errorRateMax") if key in scenario
        },
        "result": result,
        "metadataComplete": not metadata_blockers,
        "metadataBlockers": metadata_blockers,
        "passed": bool(result.get("passed")) and not metadata_blockers,
    }
    output = Path(args.output)
    if not output.is_absolute():
        output = root / output
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary.replace(output)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0 if evidence["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
