from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import threading
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


e2e_gate = _load("stage22_e2e_gate", ROOT / "tools" / "stage22_e2e_evidence_gate.py")
callback_injector = _load("stage22_callback", ROOT / "tools" / "stage22_callback_injector.py")
security_gate = _load("stage22_security", ROOT / "tools" / "stage22_security_gate.py")


def test_stage22_catalog_has_ten_cases_and_three_assertion_groups(tmp_path):
    catalog_path = ROOT / "docs" / "operations" / "stage22-e2e-cases.json"
    output = tmp_path / "gate.json"
    result = subprocess.run(
        [sys.executable, str(ROOT / "tools" / "stage22_e2e_evidence_gate.py"),
         "--catalog", str(catalog_path), "--output", str(output)],
        cwd=ROOT, text=True, capture_output=True, timeout=30, check=False,
    )
    assert result.returncode == 0, result.stderr + result.stdout
    evidence = json.loads(output.read_text(encoding="utf-8"))
    assert evidence["catalogCases"] == 10
    assert evidence["executedCases"] == 0
    assert evidence["passed"] is True


def test_stage22_execution_gate_requires_all_three_evidence_groups(tmp_path):
    catalog = json.loads((ROOT / "docs" / "operations" / "stage22-e2e-cases.json").read_text(encoding="utf-8"))
    evidence_dir = tmp_path / "evidence"
    evidence_dir.mkdir()
    now = datetime.now(timezone.utc).isoformat()
    for case in catalog["cases"]:
        payload = {
            "caseId": case["id"], "environment": "sandbox", "providerMode": case["providerMode"],
            "runId": "test-run", "sourceRevision": "a" * 40, "startedAt": now, "finishedAt": now,
            "assertions": {
                group: [{"passed": True, "evidence": f"{group}-evidence"}]
                for group in ("business", "database", "event")
            },
        }
        (evidence_dir / f"{case['id']}.json").write_text(json.dumps(payload), encoding="utf-8")
    first = evidence_dir / "E2E-01.json"
    invalid = json.loads(first.read_text(encoding="utf-8"))
    invalid["assertions"]["event"] = []
    first.write_text(json.dumps(invalid), encoding="utf-8")
    output = tmp_path / "gate.json"
    result = subprocess.run(
        [sys.executable, str(ROOT / "tools" / "stage22_e2e_evidence_gate.py"),
         "--evidence-dir", str(evidence_dir), "--output", str(output), "--require-execution"],
        cwd=ROOT, text=True, capture_output=True, timeout=30, check=False,
    )
    assert result.returncode == 2
    assert "E2E-01: no executed event assertions" in json.loads(output.read_text(encoding="utf-8"))["errors"]


class _CaptureHandler(BaseHTTPRequestHandler):
    received: list[dict] = []

    def do_POST(self):  # noqa: N802
        length = int(self.headers.get("Content-Length", "0"))
        self.received.append({"path": self.path, "body": self.rfile.read(length).decode("utf-8")})
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"success")

    def log_message(self, _format, *_args):
        return


def test_callback_injector_preserves_out_of_order_sequence_and_duplicates():
    _CaptureHandler.received = []
    server = ThreadingHTTPServer(("127.0.0.1", 0), _CaptureHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        result = callback_injector.dispatch({
            "providerMode": "CONTROLLED_STUB",
            "events": [
                {"sequence": 2, "path": "/callback", "body": {"state": "DELIVERED"}},
                {"sequence": 1, "path": "/callback", "form": {"state": "SHIPPED"}, "repeat": 2},
            ],
        }, f"http://127.0.0.1:{server.server_port}")
    finally:
        server.shutdown()
        server.server_close()
    assert result["passed"] is True
    assert [item["sequence"] for item in result["results"]] == [2, 1, 1]
    assert len(_CaptureHandler.received) == 3


def _security_fixture(tmp_path: Path):
    policy = json.loads((ROOT / "docs" / "security" / "stage22-security-scan-policy.json").read_text(encoding="utf-8"))
    report_dir = tmp_path / "reports"
    report_dir.mkdir()
    now = datetime.now(timezone.utc)
    for expected in policy["requiredReports"]:
        report = {
            "schemaVersion": "AIMALL_SECURITY_SCAN_V1", "scannerId": expected["scannerId"],
            "tool": expected["tool"], "scope": expected["scope"], "sourceRevision": "b" * 40,
            "generatedAt": now.isoformat(), "completed": True, "findings": [],
        }
        (report_dir / f"{expected['scannerId']}.json").write_text(json.dumps(report), encoding="utf-8")
    return policy, report_dir, now


def test_security_gate_blocks_cvss7_and_only_accepts_complete_unexpired_exception(tmp_path):
    policy, report_dir, now = _security_fixture(tmp_path)
    sast_path = report_dir / "SAST.json"
    sast = json.loads(sast_path.read_text(encoding="utf-8"))
    sast["findings"] = [{"id": "SAST-1", "cvss": 7.0, "severity": "HIGH"}]
    sast_path.write_text(json.dumps(sast), encoding="utf-8")
    empty = {"schemaVersion": "AIMALL_SECURITY_EXCEPTIONS_V1", "exceptions": []}
    blocked = security_gate.evaluate(policy, empty, report_dir, "b" * 40, now)
    assert blocked["passed"] is False
    assert blocked["blockingFindings"][0]["findingId"] == "SAST-1"

    exception = {
        "schemaVersion": "AIMALL_SECURITY_EXCEPTIONS_V1",
        "exceptions": [{
            "findingId": "SAST-1", "scannerId": "SAST", "owner": "security-owner",
            "reason": "No fixed release exists", "compensatingMeasures": "Feature disabled and WAF rule active",
            "expiresAt": (now + timedelta(days=7)).isoformat(), "approvedBy": "security-lead",
        }],
    }
    waived = security_gate.evaluate(policy, exception, report_dir, "b" * 40, now)
    assert waived["passed"] is True
    assert waived["waivedFindings"][0]["owner"] == "security-owner"

    exception["exceptions"][0]["expiresAt"] = (now - timedelta(seconds=1)).isoformat()
    expired = security_gate.evaluate(policy, exception, report_dir, "b" * 40, now)
    assert expired["passed"] is False
    assert expired["blockingFindings"]
