from __future__ import annotations

import argparse
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


SEVERITY_SCORE = {"UNKNOWN": 10.0, "CRITICAL": 9.0, "HIGH": 7.0, "MEDIUM": 4.0, "LOW": 1.0, "INFO": 0.0}


def parse_time(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def finding_score(finding: dict[str, Any]) -> float:
    if finding.get("cvss") is not None:
        return float(finding["cvss"])
    return SEVERITY_SCORE.get(str(finding.get("severity") or "UNKNOWN").upper(), 10.0)


def active_exception(
    exceptions: list[dict[str, Any]], scanner_id: str, finding_id: str, required: list[str], now: datetime
) -> dict[str, Any] | None:
    for item in exceptions:
        if item.get("scannerId") != scanner_id or item.get("findingId") != finding_id:
            continue
        if any(not str(item.get(field) or "").strip() for field in required):
            continue
        try:
            if parse_time(str(item["expiresAt"])) <= now:
                continue
        except Exception:
            continue
        return item
    return None


def evaluate(
    policy: dict[str, Any], exception_document: dict[str, Any], report_dir: Path,
    source_revision: str | None = None, now: datetime | None = None,
) -> dict[str, Any]:
    current = now or datetime.now(timezone.utc)
    errors: list[str] = []
    blocking: list[dict[str, Any]] = []
    waived: list[dict[str, Any]] = []
    reports_seen = 0
    exceptions = exception_document.get("exceptions") if isinstance(exception_document.get("exceptions"), list) else []
    required_exception_fields = list(policy.get("exceptionRequiredFields") or [])
    max_age = timedelta(hours=float(policy.get("maxReportAgeHours", 168)))
    threshold = float(policy.get("blockCvssAtOrAbove", 7))

    for expected in policy.get("requiredReports", []):
        scanner_id = str(expected["scannerId"])
        path = report_dir / f"{scanner_id}.json"
        if not path.exists():
            errors.append(f"missing report: {scanner_id}")
            continue
        try:
            report = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            errors.append(f"invalid report {scanner_id}: {type(exc).__name__}")
            continue
        reports_seen += 1
        if report.get("schemaVersion") != "AIMALL_SECURITY_SCAN_V1":
            errors.append(f"{scanner_id}: invalid schemaVersion")
        if report.get("scannerId") != scanner_id or report.get("tool") != expected.get("tool"):
            errors.append(f"{scanner_id}: scanner/tool mismatch")
        if report.get("scope") != expected.get("scope"):
            errors.append(f"{scanner_id}: scope mismatch")
        if report.get("completed") is not True:
            errors.append(f"{scanner_id}: scan incomplete")
        if source_revision and report.get("sourceRevision") != source_revision:
            errors.append(f"{scanner_id}: source revision mismatch")
        try:
            generated = parse_time(str(report.get("generatedAt") or ""))
            if current - generated > max_age or generated > current + timedelta(minutes=5):
                errors.append(f"{scanner_id}: stale/future report")
        except Exception:
            errors.append(f"{scanner_id}: invalid generatedAt")
        findings = report.get("findings") if isinstance(report.get("findings"), list) else []
        for finding in findings:
            score = finding_score(finding)
            if score < threshold:
                continue
            finding_id = str(finding.get("id") or "").strip()
            if not finding_id:
                errors.append(f"{scanner_id}: blocking finding without id")
                continue
            exception = active_exception(exceptions, scanner_id, finding_id, required_exception_fields, current)
            item = {"scannerId": scanner_id, "findingId": finding_id, "score": score}
            if exception:
                waived.append({**item, "owner": exception["owner"], "expiresAt": exception["expiresAt"]})
            else:
                blocking.append(item)

    return {
        "schemaVersion": "AIMALL_STAGE22_SECURITY_GATE_V1",
        "generatedAt": current.isoformat(),
        "requiredReports": len(policy.get("requiredReports", [])),
        "reportsSeen": reports_seen,
        "blockingFindings": blocking,
        "waivedFindings": waived,
        "errors": errors,
        "passed": not errors and not blocking,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="AIMall Stage 22 normalized security scan gate")
    parser.add_argument("--policy", default="docs/security/stage22-security-scan-policy.json")
    parser.add_argument("--exceptions", default="docs/security/stage22-security-exceptions.json")
    parser.add_argument("--report-dir", default=".acceptance/stage22/security")
    parser.add_argument("--source-revision")
    parser.add_argument("--output", default=".acceptance/stage22/security-gate.json")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    resolve = lambda value: Path(value) if Path(value).is_absolute() else root / value
    result = evaluate(
        json.loads(resolve(args.policy).read_text(encoding="utf-8")),
        json.loads(resolve(args.exceptions).read_text(encoding="utf-8")),
        resolve(args.report_dir), args.source_revision,
    )
    output = resolve(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
