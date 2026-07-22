from __future__ import annotations

import argparse
import base64
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlparse


def encode_event(event: dict) -> tuple[bytes, str]:
    if "rawBodyBase64" in event:
        return base64.b64decode(event["rawBodyBase64"]), str(event.get("contentType") or "application/octet-stream")
    if "form" in event:
        return urllib.parse.urlencode(event["form"]).encode("utf-8"), "application/x-www-form-urlencoded"
    return json.dumps(event.get("body", {}), separators=(",", ":")).encode("utf-8"), "application/json"


def dispatch(scenario: dict, target_base_url: str, allow_non_loopback: bool = False) -> dict:
    if str(scenario.get("providerMode") or "").upper() in {"", "SIMULATE", "FIXED_SUCCESS"}:
        raise ValueError("callback injection requires sandbox/stub provider mode")
    host = (urlparse(target_base_url).hostname or "").lower()
    if not allow_non_loopback and host not in {"127.0.0.1", "localhost", "::1"}:
        raise ValueError("non-loopback target requires explicit approval")
    results = []
    started = time.perf_counter()
    for event in scenario.get("events", []):
        time.sleep(max(0, int(event.get("delayMs", 0))) / 1000)
        repeat = max(1, int(event.get("repeat", 1)))
        for duplicate_index in range(repeat):
            body, content_type = encode_event(event)
            headers = {"Content-Type": content_type, **dict(event.get("headers") or {})}
            url = target_base_url.rstrip("/") + "/" + str(event.get("path") or "").lstrip("/")
            status = 0
            error = None
            sent_at = datetime.now(timezone.utc).isoformat()
            try:
                request = urllib.request.Request(url, data=body, headers=headers, method=str(event.get("method") or "POST"))
                with urllib.request.urlopen(request, timeout=float(event.get("timeoutSeconds", 10))) as response:
                    status = int(response.status)
                    response.read(4096)
            except urllib.error.HTTPError as exc:
                status = int(exc.code)
                error = f"HTTP_{status}"
            except Exception as exc:
                error = type(exc).__name__
            expected = {int(value) for value in event.get("expectedStatuses", [200])}
            results.append({
                "sequence": event.get("sequence"), "duplicateIndex": duplicate_index,
                "sentAt": sent_at, "status": status, "error": error, "passed": status in expected,
            })
    return {
        "schemaVersion": "AIMALL_CALLBACK_INJECTION_V1",
        "providerMode": scenario["providerMode"],
        "eventCount": len(results),
        "elapsedMs": round((time.perf_counter() - started) * 1000, 3),
        "results": results,
        "passed": bool(results) and all(item["passed"] for item in results),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Inject delayed, duplicate and out-of-order Stage 22 callbacks")
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--target-base-url", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--allow-non-loopback", action="store_true")
    args = parser.parse_args()
    scenario = json.loads(Path(args.scenario).read_text(encoding="utf-8"))
    result = dispatch(scenario, args.target_base_url, args.allow_non_loopback)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
