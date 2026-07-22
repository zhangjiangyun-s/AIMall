# AIMall script inventory

This document records which repository scripts are public entry points, which are dependency-bound quality controls, and which historical scripts were removed from the public tree.

## Root entry points

Only application and Docker startup entry points remain in the repository root:

| File | Responsibility | Dependency |
| --- | --- | --- |
| `start.bat` | Windows local-development shortcut | Calls `start-local.ps1` |
| `start-local.ps1` | Starts Java, storefront, admin, and AI processes | JDK, Maven, Node.js, npm, Python |
| `docker-full-start.bat` | Windows Docker shortcut | Calls `docker-full-start.ps1` |
| `docker-full-start.ps1` | Builds and starts the full Compose stack | `docker-compose.full.yml`, `.env.docker.local` |
| `docker-full-stop.bat` | Stops the full stack without deleting volumes | `docker-compose.full.yml` |

## Development utilities

`scripts/dev/reset-local-db.ps1` is intentionally outside the root directory. It is destructive and requires `-ConfirmReset`. It refuses to run when `AIMALL_ENVIRONMENT` is `prod` or `production`, and it requires an explicit database password or a configured environment variable.

The `.bat` file beside it is only a Windows shortcut.

## Continuous integration

The default push and pull-request workflow is `.github/workflows/stage10-quality.yml`. Its static gates require:

- `scripts/stage7-security-gate.ps1`
- `scripts/stage8-encoding-gate.ps1`
- `scripts/stage9-observability-gate.ps1`
- `scripts/stage10-quality-gate.ps1`

The Stage 10 quality gate also validates the presence of migration, backup, browser, Redis, and Milvus regression assets. The remaining `scripts/stage10-*` files are retained as reproducible drill utilities rather than root entry points.

`.github/workflows/stage22-security.yml` is the manual SAST, dependency, secret, image, IaC, and DAST workflow. It is self-contained and does not depend on local acceptance output.

## Operations and release controls

The following groups remain public because tests, runbooks, or downstream controls consume them:

- Stage 14-19 quality gates produce inputs consumed by later release decisions.
- Stage 20 scripts implement Flyway governance and encrypted backup/restore drills.
- Stage 21 scripts and tools implement capacity tests and canary monitoring.
- Stage 22 scripts and tools implement E2E evidence and security normalization.
- Stage 23-26 Python tools remain covered by automated tests as release-policy evaluators.
- `tools/import_policy_knowledge.py` and the evaluation/scoring tools support RAG data operations.
- `tools/stage6_milvus_rebuild_acceptance.py` and `tools/stage6_rag_benchmark.py` are referenced by the public Stage 6 runbook.

Stage 23-26 GitHub workflows were removed from the public workflow list because they required ignored `.acceptance` files without downloading a trusted upstream artifact. The underlying tested policy evaluators remain available for controlled release systems that provide their own evidence bundle.

## Removed obsolete database scripts

The following root scripts were deleted:

- `migrate-db.bat`
- `migrate-db.ps1`
- `seed-products.bat`
- `seed-products.ps1`

They directly executed dated SQL, bypassed Flyway, assumed an obsolete container name, and could fall back to the password `123456`. Database schema changes must use Flyway. Development seed data must be managed through versioned fixtures or an explicit development profile.

## Locally archived historical scripts

The following categories had no active dependency from GitHub Actions, tests, public runbooks, or downstream release controls and were moved to `.local-archive/historical-scripts/`:

- Stage 11 delivery and quality scripts
- Stage 12 outage, on-call, reverse-review, and quality scripts
- Stage 13 OpenAPI, launch-signoff, and quality scripts
- Phase 11-16 one-time Python acceptance drivers
- The standalone Phase 23 RAG acceptance driver
- Stage 12 LLM-timeout and Milvus-reconnect helper scripts
- Stage 23-26 GitHub workflows that could not obtain their required evidence

`.local-archive/` is ignored by Git. It preserves local history without publishing obsolete execution entry points.

## Maintenance rules

1. A new script must have a documented caller or operator use case.
2. Production database scripts must not bypass Flyway.
3. Destructive scripts must require explicit confirmation and reject production environments by default.
4. CI workflows must not depend on ignored local files unless they securely download a verified artifact.
5. Phase-specific one-time scripts should be archived after their behavior is covered by stable automated tests.
