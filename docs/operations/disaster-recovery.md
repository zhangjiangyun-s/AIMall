# AIMall Disaster Recovery Runbook

## Scope And Targets

| System | Source of truth | RPO | RTO | Recovery method |
|---|---|---:|---:|---|
| MySQL | Yes | <= 5 min | <= 60 min | Encrypted full backup plus continuous ROW binlog/PITR |
| Redis session | No | <= 15 min | <= 30 min | AOF everysec, periodic RDB, encrypted offsite copy |
| Redis Action/rate limit | Operational state | No silent loss | <= 30 min | Restore AOF; fail closed until ownership and leases are valid |
| Milvus | No | Rebuildable | <= 120 min | Rebuild from MySQL current-version chunks and embedding cache |

These are conservative baselines until production capacity testing records measured values.

## Backup Policy

1. Use a dedicated MySQL account with only `SELECT`, `SHOW VIEW`, `TRIGGER`, and `EVENT` on the AIMall schema. Root is permitted only to create an isolated restore database during a drill.
2. Run an encrypted full backup daily. `scripts/stage20-encrypted-backup-restore-drill.ps1` writes plaintext only under the operating-system temporary directory, encrypts with PBKDF2-SHA256/AES-256-CBC/HMAC-SHA256, verifies the HMAC before restore, and deletes temporary plaintext in `finally`.
3. Ship MySQL ROW binlogs continuously. The archive monitor must alert when the last confirmed offsite object is older than 300 seconds. A daily full backup does not satisfy the five-minute RPO without binlogs.
4. Store backups in an account and region separate from the application account. Enable object lock/versioning, server-side encryption in addition to client encryption, and a 30-day minimum retention policy.
5. Keep the encryption key in KMS or a secret manager. Do not place it in `.env`, Compose, evidence JSON, command history, or the backup directory.
6. Redis uses AOF `everysec` and RDB snapshots. Copy an RDB/AOF checkpoint to encrypted offsite storage at least every 15 minutes. Never restore Action state into an instance accepting traffic until lease clocks and ownership fencing have been checked.
7. Milvus data is not backed up as a business fact. Collection metadata may be saved for diagnostics, but authoritative recovery uses MySQL chunks and embedding cache.

## MySQL PITR Procedure

1. Declare an incident, stop transaction writers, record the target UTC recovery timestamp, and preserve provider callbacks in the ingress buffer.
2. Select the newest full backup before the target. Verify object version, SHA-256, HMAC, encryption-key ID, retention lock, and backup account identity.
3. Restore to a new isolated schema or server. Never overwrite the damaged schema in place.
4. Replay ROW binlogs from the backup coordinates to the target UTC timestamp. Record the last applied GTID/file-position and prove the gap to the target is at most five minutes.
5. Run Flyway validate/migrate. The restored schema must reach the repository's latest migration before application traffic is enabled.
6. Execute the domain acceptance checks below. Any count regression, new orphan, malformed Outbox payload, negative/overlocked SKU, or missing knowledge version blocks cutover.
7. Start one read-only application instance, run synthetic order/payment/refund and knowledge queries, then enable writers through a feature flag.
8. Preserve the failed environment and all evidence until incident review is complete.

## Required Domain Acceptance

Recovery is not accepted by a successful database connection alone. Compare the source recovery point and restored system for:

- orders and order items;
- payment records and verified callback evidence;
- refund records and return applications;
- inventory ledger plus `stock >= lock_stock >= 0`;
- Outbox rows, payload JSON, attempts, sent/dead state;
- administrator and knowledge audit logs;
- knowledge documents, current versions, chunks, publication version and retrieval epoch;
- Flyway current version and validation result.

The automated local implementation is `scripts/stage20-encrypted-backup-restore-drill.ps1`. It compares row count plus maximum ID for eight fact tables and checks six referential/business invariants before reporting RTO.

## Redis Recovery

1. Put AI Action/write routes into fail-closed mode and keep ordinary read-only chat explicitly degraded.
2. Restore the newest offsite AOF/RDB to an isolated Redis instance with authentication enabled.
3. Verify AOF loading, key schema version, session TTLs, Action owner fingerprint, action version, execution token and lease timestamps.
4. Expire stale `EXECUTING` records only through the normal fencing/takeover protocol. Never bulk-change them to `PENDING`.
5. Verify gateway/SSE rate limits are active before enabling chat traffic.
6. Run one persisted Action takeover drill and prove exactly one Java execution callback before cutover.

## Milvus Rebuild

1. Mark vector readiness down; policy answers must refuse or use an explicitly approved document fallback.
2. Create a fresh collection with the expected embedding dimension and dynamic metadata fields.
3. From the admin knowledge operation, enqueue rebuild tasks for every current document version. Each task receives a new execution token, which causes stale physical vector IDs to be replaced safely.
4. Monitor task success, dead letters, vector-delete backlog and embedding cache hit rate.
5. Compare MySQL active chunk count with Milvus active vector count by tenant, document version, publication version and retrieval epoch.
6. Run `evalset-v1` three times and pass the Stage 19 worst-case gate before switching retrieval traffic.
7. Keep the old collection until the new collection passes consistency and quality checks; switch by configuration and retain a rollback pointer.

## Drill Cadence And Evidence

- Run a full recovery drill at least quarterly and after material schema, backup-provider or encryption changes.
- Record backup object version, SHA-256, key ID, source recovery point, binlog endpoint, start/end times, measured RPO/RTO, Flyway version and every domain assertion.
- The production drill must use production-sized data and separate infrastructure. Local evidence cannot satisfy production readiness.
- Owners: DBA for MySQL/PITR, SRE for storage and Redis, AI platform for Milvus, Backend for domain invariants, Security for keys and access, QA for acceptance.

## Failure And Rollback

- Never run a destructive down migration. Expanded columns and tables remain after application rollback.
- A failed backfill resumes from its high-water mark; a failed contract operation requires the signed pre-change backup and maintenance decision.
- If restored data fails any domain invariant, do not cut over. Return to the previous read-only environment or restore an earlier backup and replay binlogs again.
- If RPO or RTO exceeds the target, record the measured failure and keep production readiness false until a repeated drill passes.
