param(
    [switch]$RequireEngineering,
    [switch]$RequireProduction,
    [string]$Output = '.acceptance/stage20/quality-gate.json'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Read-Json([string]$relativePath) {
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing Stage 20 evidence: $relativePath"
    }
    return Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Read-Utf8([string]$relativePath) {
    return [IO.File]::ReadAllText((Join-Path $root $relativePath), [Text.Encoding]::UTF8)
}

function Add-Check(
    [Collections.Generic.List[object]]$checks,
    [string]$id,
    [string]$name,
    [bool]$engineeringReady,
    [bool]$productionReady,
    [string]$evidence,
    [string]$productionBlocker
) {
    $checks.Add([ordered]@{
        id = $id
        name = $name
        engineeringReady = $engineeringReady
        productionReady = $productionReady
        evidence = $evidence
        productionBlocker = $productionBlocker
    })
}

& (Join-Path $PSScriptRoot 'stage20-validate-migration-governance.ps1') | Out-Null

$governance = Read-Json '.acceptance/stage20/migration-governance.json'
$governanceSource = Read-Json 'docs/operations/migration-governance.json'
$backup = Read-Json '.acceptance/stage20/backup-restore/result.json'
$currentMigration = Read-Json '.acceptance/stage20/current-flyway-migration.json'
$verification = Read-Json '.acceptance/stage20/verification.json'
$compose = Read-Utf8 'docker-compose.yml'
$runbook = Read-Utf8 'docs/operations/disaster-recovery.md'
$envExample = Read-Utf8 '.env.example'

$migrationFiles = @(Get-ChildItem -LiteralPath (Join-Path $root 'aimall-server/src/main/resources/db/migration') -Filter 'V*.sql' -File)
$requiredProfileFields = @(
    'estimatedLockTime', 'tableSizeQuery', 'onlineDdl', 'batchSize',
    'progressQuery', 'failureRecovery', 'backupPoint', 'compatibilityWindow'
)
$profilesComplete = $true
foreach ($profile in $governanceSource.profiles.PSObject.Properties.Value) {
    foreach ($field in $requiredProfileFields) {
        if ([string]::IsNullOrWhiteSpace([string]$profile.$field)) { $profilesComplete = $false }
    }
}

$backupPath = Join-Path $root ([string]$backup.encryptedBackup)
$backupHashMatches = $false
if (Test-Path -LiteralPath $backupPath -PathType Leaf) {
    $backupHashMatches = (Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash -eq $backup.encryptedBackupSha256
}
$snapshotNames = @(
    'oms_order', 'oms_payment_record', 'oms_refund_record', 'inventory_ledger',
    'outbox_event', 'admin_operation_audit', 'knowledge_doc_audit_log', 'knowledge_doc_version'
)
$snapshotsComplete = @($snapshotNames | Where-Object { $null -eq $backup.domainSnapshots.$_ }).Count -eq 0
$invariants = @($backup.invariants.PSObject.Properties.Value)
$invariantsPassed = $invariants.Count -eq 6 -and @($invariants | Where-Object { -not $_.noRegression }).Count -eq 0

$checks = [Collections.Generic.List[object]]::new()
Add-Check $checks 'S20-01' 'Flyway migration governance coverage' `
    ([bool]$governance.passed -and $governance.migrationCount -eq 29 -and $governance.metadataCount -eq 29 -and $migrationFiles.Count -eq 29) `
    $false `
    '29/29 Flyway migrations have owner, phase and an operational profile.' `
    'No production-sized table statistics or measured lock-time review has been signed.'
Add-Check $checks 'S20-02' 'Expand/contract metadata and destructive approval gate' `
    ($profilesComplete -and $governance.profileCount -eq 5 -and $governance.destructiveApprovalGates -eq 8) `
    $false `
    'Five profiles contain all eight required fields; eight contract/destructive migrations require separate approval.' `
    'No DBA/change-approval evidence exists for a production deployment window.'
Add-Check $checks 'S20-03' 'Encrypted least-privilege backup artifact' `
    ([bool]$backup.passed -and $backupHashMatches -and -not [bool]$backup.plaintextPersisted -and
        -not [bool]$backup.backupPrincipal.rootUsedForDump -and [bool]$backup.backupPrincipal.ephemeral -and
        $backup.encryption -eq 'PBKDF2-SHA256-200000/AES-256-CBC/HMAC-SHA256') `
    $false `
    'Encrypted backup hash verified; dump principal was ephemeral and non-root; plaintext was removed.' `
    'No KMS-managed key, immutable offsite object, secondary account/region or retention evidence exists.'
Add-Check $checks 'S20-04' 'Isolated restore, migration and domain acceptance' `
    ([bool]$backup.passed -and [bool]$backup.rtoPassed -and $backup.rtoSeconds -le $backup.rtoTargetSeconds -and
        $backup.latestFlywayVersion -eq '20260722.0900' -and $snapshotsComplete -and $invariantsPassed) `
    $false `
    "Isolated restore migrated to 20260722.0900 in $($backup.rtoSeconds)s and preserved eight domain snapshots with six invariants." `
    'No production PITR/binlog replay drill on production-sized separate infrastructure has been completed.'
Add-Check $checks 'S20-05' 'RPO/RTO configuration and disaster-recovery runbook' `
    ($compose.Contains('--binlog-format=ROW') -and $compose.Contains('--sync-binlog=1') -and
        $compose.Contains('--appendonly') -and $compose.Contains('--appendfsync') -and
        $envExample.Contains('AIMALL_BINLOG_ARCHIVE_INTERVAL_SECONDS=300') -and
        $runbook.Contains('| MySQL | Yes | <= 5 min | <= 60 min |') -and
        $runbook.Contains('| Milvus | No | Rebuildable | <= 120 min |') -and
        $runbook.Contains('quarterly')) `
    $false `
    'Compose durability settings, offsite variables and MySQL/Redis/Milvus recovery procedures are documented.' `
    'No live Redis offsite restore, Action takeover, or timed full Milvus rebuild drill exists.'
Add-Check $checks 'S20-06' 'Current schema migration and regression verification' `
    ([bool]$currentMigration.passed -and $currentMigration.database -eq 'aimall' -and
        $currentMigration.latestFlywayVersion -eq '20260722.0900' -and
        $currentMigration.backupSha256 -eq $backup.encryptedBackupSha256 -and
        [bool]$verification.python.passed -and [bool]$verification.java.passed -and
        [bool]$verification.realMySql.passed -and [bool]$verification.compose.passed) `
    $false `
    'Current aimall schema migrated behind an explicit approval guard; Python 336, Java 188 and real MySQL 25 tests passed.' `
    'No quarterly cross-role production recovery drill and SRE/Security/DBA/QA sign-off exists.'

$result = [ordered]@{
    stage = 20
    scope = 'Flyway migration governance, encrypted backup and disaster recovery'
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    total = $checks.Count
    engineeringPassedCount = @($checks | Where-Object engineeringReady).Count
    engineeringPassed = @($checks | Where-Object { -not $_.engineeringReady }).Count -eq 0
    productionPassedCount = @($checks | Where-Object productionReady).Count
    productionPassed = @($checks | Where-Object { -not $_.productionReady }).Count -eq 0
    checks = $checks
}

$outputPath = Join-Path $root $Output
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputPath)) | Out-Null
[IO.File]::WriteAllText($outputPath, ($result | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
$result | ConvertTo-Json -Depth 20

if (($RequireEngineering -and -not $result.engineeringPassed) -or
    ($RequireProduction -and -not $result.productionPassed)) {
    throw "Stage 20 quality gate failed: $outputPath"
}
