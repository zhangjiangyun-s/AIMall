# AIMall Performance, Capacity And Canary Runbook

## Capacity Evidence

`docs/operations/capacity-gates.json` is the versioned source for initial thresholds. Every accepted run must preserve the run ID, UTC timestamps, source revision, hardware, data volume, model, cache hit rate and concurrency model. A report missing any of these fields is invalid even when latency is low.

Use `tools/stage21_capacity_runner.py` against isolated test data. Write scenarios are disabled unless `--approve-write-scenarios` is supplied. Login, order and payment callback payloads must come from dedicated Stage 21 environment variables. Order payloads may contain `{{requestId}}`; the runner replaces it for every warmup and measured request.

Example read-only AI run:

```powershell
python tools/stage21_capacity_runner.py --scenario AI_REQUEST_CONCURRENCY `
  --output .acceptance/stage21/runs/ai.json --data-volume "orders=100000,products=50000" `
  --model "agnes-2.0-flash" --cache-hit-rate "0.73"
```

## Initial Gates

- Login: 100 RPS, P95 at most 300ms.
- Order create: 30 RPS, P95 at most 800ms and P99 at most 1500ms.
- Payment callback: 100 RPS, P95 at most 300ms, with verified evidence committed first.
- AI: 20 concurrent requests, P95 at most 8s, with explicit degradation on overload.
- SSE: 200 concurrent connections per instance and four per authenticated client by default.
- Redis and MySQL pools: below 70 percent utilization.
- MySQL row-lock wait: the available maximum-wait metric is used as a conservative upper bound and must remain below 500ms.
- Milvus/RAG retrieval: P95 at most 500ms.

## Release Soak Gate

The release candidate must run continuously for at least 3,600 seconds with production-equivalent data and topology. The accepted report must prove CPU below 70 percent, memory below 75 percent, database pool utilization below 70 percent, P99 lock wait below 500ms and business error rate below 0.1 percent. It must also record instance specifications, model version, concurrency, cache hit rate and observed degradation behavior. Short scenario runs are diagnostic evidence and cannot replace this 60-minute gate.

## Canary Eligibility

`docs/operations/canary-release-policy.json` starts with empty tenant and user allowlists. A request is eligible only when both trusted tenant and authenticated user are present in the policy. Client headers are not trusted identity. The deployment controller must evaluate the policy using token-derived identity before routing to the candidate version.

The policy and normalized runtime metrics are evaluated by `tools/stage21_canary_gate.py`. Decisions and trips use atomic replacement; the JSONL audit is flushed and fsynced. A trip persists in `.acceptance/stage21/canary-state.json`. Recovery never automatically reopens the candidate. Reset requires `--reset-trip --change-id <approved-change>`.

## Automatic Trip

Run `scripts/stage21-canary-monitor.ps1` as an external deployment monitor. It queries Prometheus every 30 seconds and closes the release when any required metric is absent or when one of these conditions occurs:

- payment difference count is nonzero;
- invalid inventory count is nonzero;
- the independent tenant-isolation synthetic reports leakage;
- AI citation error rate is nonzero;
- HTTP 5xx rate exceeds one percent.

The independent synthetic must publish `aimall_release_tenant_leakage`. Absence is a fail-closed condition, not an assumed zero. Alerts in `docker/observability/alerts.yml` additionally enforce latency, connection-pool and lock-wait thresholds.

## Rollback

1. Persist the trip before changing traffic weights.
2. Route the tenant/user cohort to the previous application revision; never fall back to simulated payment or an unfenced AI state backend.
3. Preserve metrics, traces, request samples, provider evidence and the canary audit.
4. Reconcile payment, inventory, tenant isolation and citations before considering reset.
5. Reset only with a reviewed change ID and rerun the same capacity profile.

Local runs establish executable engineering evidence only. SRE must repeat the full matrix with production-sized data and deployment topology before production sign-off.
