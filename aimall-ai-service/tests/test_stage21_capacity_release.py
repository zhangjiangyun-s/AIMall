from __future__ import annotations

import json
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "tools" / "stage21_capacity_runner.py"
CANARY = ROOT / "tools" / "stage21_canary_gate.py"


class _Handler(BaseHTTPRequestHandler):
    def do_GET(self):  # noqa: N802
        payload = b'{"status":"ok"}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, _format, *_args):
        return


def _run(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, *args],
        cwd=ROOT,
        text=True,
        capture_output=True,
        timeout=30,
        check=False,
    )


def test_capacity_runner_records_required_metadata_and_thresholds(tmp_path):
    server = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        manifest = {
            "schemaVersion": "AIMALL_CAPACITY_GATES_V1",
            "scenarios": [{
                "id": "SELF_TEST", "kind": "HTTP", "base": "java", "path": "/health",
                "method": "GET", "sideEffect": False, "targetRps": 10, "concurrency": 2,
                "warmupSeconds": 0, "durationSeconds": 0.2, "timeoutSeconds": 2,
                "p95Ms": 1000, "p99Ms": 1000, "errorRateMax": 0, "expectedStatuses": [200],
            }],
        }
        manifest_path = tmp_path / "manifest.json"
        output_path = tmp_path / "result.json"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        result = _run(
            str(RUNNER), "--manifest", str(manifest_path), "--scenario", "SELF_TEST",
            "--output", str(output_path), "--java-base-url", f"http://127.0.0.1:{server.server_port}",
            "--duration-seconds", "0.2", "--warmup-seconds", "0", "--max-requests", "2",
            "--data-volume", "fixture=2", "--model", "stub", "--cache-hit-rate", "1.0",
        )
    finally:
        server.shutdown()
        server.server_close()
    assert result.returncode == 0, result.stderr + result.stdout
    evidence = json.loads(output_path.read_text(encoding="utf-8"))
    assert evidence["passed"] is True
    assert evidence["metadataComplete"] is True
    assert evidence["sourceRevision"] != "unavailable"
    assert evidence["result"]["requestCount"] == 2
    assert evidence["result"]["checks"] == {"errorRate": True, "p95": True, "p99": True, "requestCount": True}
    for field in ("runId", "startedAt", "sourceRevision", "hardware", "dataVolume", "model", "cacheHitRate", "concurrencyModel"):
        assert field in evidence


def test_capacity_runner_refuses_unapproved_write_scenario(tmp_path):
    manifest = {
        "schemaVersion": "AIMALL_CAPACITY_GATES_V1",
        "scenarios": [{
            "id": "WRITE", "kind": "HTTP", "base": "java", "path": "/write",
            "method": "POST", "sideEffect": True, "targetRps": 1, "concurrency": 1,
            "durationSeconds": 1, "errorRateMax": 0, "expectedStatuses": [200],
        }],
    }
    manifest_path = tmp_path / "manifest.json"
    output_path = tmp_path / "result.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    result = _run(str(RUNNER), "--manifest", str(manifest_path), "--scenario", "WRITE", "--output", str(output_path))
    assert result.returncode == 2
    evidence = json.loads(output_path.read_text(encoding="utf-8"))
    assert evidence["passed"] is False
    assert "--approve-write-scenarios" in evidence["result"]["blockers"][0]


def test_canary_requires_both_allowlists_and_persists_threshold_trip(tmp_path):
    policy = {
        "schemaVersion": "AIMALL_CANARY_POLICY_V1",
        "feature": "test-release",
        "state": "CANARY",
        "tenantAllowlist": ["tenant-a"],
        "userAllowlist": ["42"],
        "requireTenantAndUserMatch": True,
        "thresholds": {
            "paymentDifferenceCount": 0, "inventoryInvalidCount": 0,
            "tenantLeakageCount": 0, "aiCitationErrorRate": 0, "errorRate": 0.01,
        },
        "requiredMetricFields": [
            "paymentDifferenceCount", "inventoryInvalidCount", "tenantLeakageCount",
            "aiCitationErrorRate", "errorRate",
        ],
    }
    good = {
        "paymentDifferenceCount": 0, "inventoryInvalidCount": 0, "tenantLeakageCount": 0,
        "aiCitationErrorRate": 0, "errorRate": 0.001,
    }
    policy_path = tmp_path / "policy.json"
    metrics_path = tmp_path / "metrics.json"
    output_path = tmp_path / "decision.json"
    state_path = tmp_path / "state.json"
    audit_path = tmp_path / "audit.jsonl"
    policy_path.write_text(json.dumps(policy), encoding="utf-8")
    metrics_path.write_text(json.dumps(good), encoding="utf-8")

    base = [
        str(CANARY), "--policy", str(policy_path), "--metrics", str(metrics_path),
        "--tenant", "tenant-a", "--output", str(output_path),
        "--state-file", str(state_path), "--audit-file", str(audit_path),
    ]
    allowed = _run(*base, "--user", "42")
    assert allowed.returncode == 0
    assert json.loads(output_path.read_text(encoding="utf-8"))["decision"] == "ALLOW"

    not_allowlisted = _run(*base, "--user", "41")
    assert not_allowlisted.returncode == 3
    assert json.loads(output_path.read_text(encoding="utf-8"))["decision"] == "CLOSED"

    metrics_path.write_text(json.dumps({**good, "inventoryInvalidCount": 1}), encoding="utf-8")
    tripped = _run(*base, "--user", "42")
    assert tripped.returncode == 3
    assert json.loads(state_path.read_text(encoding="utf-8"))["state"] == "TRIPPED"

    metrics_path.write_text(json.dumps(good), encoding="utf-8")
    still_closed = _run(*base, "--user", "42")
    assert still_closed.returncode == 3
    decision = json.loads(output_path.read_text(encoding="utf-8"))
    assert "PERSISTED_TRIP" in decision["violations"]
    assert len(audit_path.read_text(encoding="utf-8").splitlines()) == 4


def test_canary_missing_metric_fails_closed(tmp_path):
    policy = {
        "schemaVersion": "AIMALL_CANARY_POLICY_V1", "feature": "test", "state": "FULL",
        "tenantAllowlist": [], "userAllowlist": [], "requireTenantAndUserMatch": True,
        "thresholds": {"tenantLeakageCount": 0}, "requiredMetricFields": ["tenantLeakageCount"],
    }
    policy_path = tmp_path / "policy.json"
    metrics_path = tmp_path / "metrics.json"
    output_path = tmp_path / "output.json"
    policy_path.write_text(json.dumps(policy), encoding="utf-8")
    metrics_path.write_text("{}", encoding="utf-8")
    result = _run(
        str(CANARY), "--policy", str(policy_path), "--metrics", str(metrics_path),
        "--tenant", "t", "--user", "u", "--output", str(output_path),
        "--state-file", str(tmp_path / "state.json"), "--audit-file", str(tmp_path / "audit.jsonl"),
    )
    assert result.returncode == 3
    assert "MISSING_METRIC:tenantLeakageCount" in json.loads(output_path.read_text(encoding="utf-8"))["violations"]
