# AIMall Stage 22 E2E And Security Runbook

## E2E Contract

`docs/operations/stage22-e2e-cases.json` is the only case catalog. Every executed case must produce `.acceptance/stage22/e2e/E2E-NN.json` with:

- `environment` equal to `sandbox` or `production-equivalent`;
- a non-simulated `providerMode`;
- run ID, source revision and UTC start/end timestamps;
- at least one passed assertion with concrete evidence in each group: `business`, `database`, `event`.

HTTP success alone is never sufficient. Database evidence should contain query IDs and sanitized expected/actual values. Event evidence should identify the audit, ledger, Outbox or callback fact without embedding credentials or full personal data.

Validate the catalog during development:

```powershell
python tools/stage22_e2e_evidence_gate.py
```

Require all ten runtime evidence files before release:

```powershell
python tools/stage22_e2e_evidence_gate.py --require-execution
```

## Provider And Callback Faults

Payments and refunds use the Alipay sandbox or a protocol-compatible controlled stub. `SIMULATE` and fixed-success handlers are forbidden. The callback injector transports exact raw bytes through `rawBodyBase64`, so a sandbox-signed payload can be replayed without reserializing and invalidating its signature.

`tools/stage22_callback_injector.py` supports per-event delay, repeat count and explicit sequence. Events are dispatched in listed order, allowing a later business sequence to arrive first. Non-loopback targets require `--allow-non-loopback` and a reviewed sandbox target.

Example:

```powershell
python tools/stage22_callback_injector.py `
  --scenario docs/operations/stage22-callback-scenario.example.json `
  --target-base-url http://127.0.0.1:8080 `
  --output .acceptance/stage22/callback-injection.json
```

After injection, the E2E driver must query the database and event/audit timeline. Injector transport success does not prove business correctness.

## Security Matrix

`docs/security/stage22-security-scan-policy.json` fixes tools and scope:

- Semgrep SAST;
- OWASP Dependency Check for Maven, npm audit for Web/Admin, pip-audit for Python;
- Trivy image scanning;
- OWASP ZAP baseline DAST;
- Gitleaks full-history/worktree scan;
- Checkov Dockerfile, Compose and GitHub Actions scan.

Each tool is normalized to one `AIMALL_SECURITY_SCAN_V1` JSON report. All nine reports must target the same source revision and be no older than seven days. `tools/stage22_security_gate.py` fails on missing, incomplete, stale, wrong-scope or wrong-revision reports.

CVSS `>= 7` and findings without a usable score are blocking. An exception is accepted only when finding ID, scanner ID, owner, reason, compensating measures, expiry and approver are all present and the expiry is in the future. Exceptions never make a scan optional.

The full external tool matrix is defined in `.github/workflows/stage22-security.yml`. DAST must target a deployed sandbox with test identities and synthetic data. Image references must be immutable release candidates, not mutable `latest` tags.

## Release Decision

Production readiness requires all ten E2E cases, all nine security reports, no unwaived blocking finding, successful failure recovery, and QA/Security/DBA/SRE/Backend/business sign-off. Local unit tests, a mocked browser flow and dependency-only scans are engineering evidence but cannot satisfy this production gate.
