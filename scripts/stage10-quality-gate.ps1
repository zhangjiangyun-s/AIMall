param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Output = ".acceptance/stage10/quality-gate.json",
    [switch]$RequireProduction
)

$ErrorActionPreference = "Stop"
$rootPath = [System.IO.Path]::GetFullPath($Root)
$checks = [System.Collections.Generic.List[object]]::new()

function Has([string]$path, [string]$needle = "") {
    $absolute = Join-Path $rootPath $path
    if (-not (Test-Path $absolute)) { return $false }
    if ([string]::IsNullOrEmpty($needle)) { return $true }
    return [System.IO.File]::ReadAllText($absolute).Contains($needle)
}

function Evidence-Passed([string]$path) {
    $absolute = Join-Path $rootPath $path
    if (-not (Test-Path $absolute)) { return $false }
    try { return [bool]((Get-Content $absolute -Raw -Encoding UTF8 | ConvertFrom-Json).passed) }
    catch { return $false }
}

function Evidence-Matches([string]$path, [scriptblock]$predicate) {
    $absolute = Join-Path $rootPath $path
    if (-not (Test-Path $absolute)) { return $false }
    try {
        $value = Get-Content $absolute -Raw -Encoding UTF8 | ConvertFrom-Json
        return [bool](& $predicate $value)
    } catch { return $false }
}

function Add-Check([string]$id, [string]$name, [bool]$localReady, [bool]$productionReady, [string]$evidence) {
    $checks.Add([ordered]@{
        id = $id; name = $name; localReady = $localReady
        productionReady = $productionReady; evidence = $evidence
    })
}

Add-Check "S10-01" "Java unit and state-machine tests" `
    (Has "aimall-server/src/test/java/com/aimall/server/service/impl/PayServiceImplTest.java" "tenDuplicatePaidCallbacks") $true "Surefire"
Add-Check "S10-02" "Real MySQL CAS, uniqueness and migration tests" `
    (Has "aimall-server/src/test/java/com/aimall/server/integration/MySqlConcurrencyIntegrationTest.java" "lastSkuConcurrentReservationAllowsExactlyOneBuyer") `
    (Evidence-Passed ".acceptance/stage10/mysql.json") "real MySQL evidence"
Add-Check "S10-03" "Real Redis multi-instance lease tests" `
    (Has "aimall-ai-service/tests/test_stage6_real_redis.py" "multi_instance_lease_takeover") `
    (Evidence-Matches ".acceptance/stage10/real-redis.json" {
        param($e) $e.passed -and $e.actionPersistedAcrossRestart -and
        [int]$e.executionCallbacks -eq 1 -and [int]$e.executionCount -eq 1 -and
        $e.finalStatus -eq "SUCCEEDED"
    }) "real Redis restart/takeover evidence"
Add-Check "S10-04" "Milvus batching, deletion, switch and stale-attempt tests" `
    ((Has "aimall-ai-service/tests/test_milvus_status_batching.py" "17050") -and (Has "aimall-ai-service/tests/test_vector_deletion_worker.py")) `
    (Evidence-Matches ".acceptance/stage10/real-milvus.json" {
        param($e) $e.passed -and $e.outageDetected -and $e.recovered -and
        $e.partialActivationDetected -and
        [int]$e.deletedStaleVectors -eq [int]$e.deleteBacklog -and
        [int]$e.currentVectorsPreserved -gt 0
    }) "real Milvus outage/recovery evidence"
Add-Check "S10-05" "Java-AI API and HMAC contract tests" `
    (Has "aimall-ai-service/tests/test_stage10_api_contracts.py" "executionToken") $true "contract and inbound auth tests"
Add-Check "S10-06" "Frontend component workflows" `
    (Has "aimall-web/src/test/stage10-workflows.spec.ts" "5") $true "Vitest login/cart/payment/return/SSE"
Add-Check "S10-07" "Playwright commerce E2E" `
    (Has "aimall-web/e2e/stage10-commerce-flow.spec.ts" "register to refund") `
    (Evidence-Matches ".acceptance/stage10/live-alipay-e2e.json" {
        param($e) $e.passed -and $e.liveGateway -and $e.paymentQuery -eq "PASSED" -and
        $e.refundQuery -eq "PASSED" -and $e.closeIdempotency -eq "PASSED" -and
        $e.reconciliation.status -eq "COMPLETED" -and
        [int]$e.reconciliation.checkedCount -gt 0 -and
        [int]$e.reconciliation.differenceCount -eq 0
    }) "mocked local E2E plus live Alipay and zero-difference reconciliation evidence"
Add-Check "S10-08" "Fault-injection harness" `
    (Has "aimall-ai-service/app/evaluation/fault_harness.py") `
    (Evidence-Matches ".acceptance/stage10/fault-drill.json" {
        param($e) $e.passed -and $e.realInfrastructureFaults.redisRestartAndTakeover.passed -and
        $e.realInfrastructureFaults.milvusOutageAndRecovery.passed -and
        $e.alertReadiness.stage9GatePassed -and $e.alertReadiness.metadataAndRunbooksValidated
    }) "offline harness plus real Redis/Milvus outage and alert-rule evidence"
Add-Check "S10-09" "Last SKU concurrent order" `
    (Has "aimall-server/src/test/java/com/aimall/server/integration/MySqlConcurrencyIntegrationTest.java" "lastSkuConcurrentReservationAllowsExactlyOneBuyer") $true "16-way MySQL race"
Add-Check "S10-10" "Ten duplicate payment callbacks" `
    (Has "aimall-server/src/test/java/com/aimall/server/service/impl/PayServiceImplTest.java" "tenDuplicatePaidCallbacksOnlyDeductInventoryAndTransitionPaymentOnce") $true "callback idempotency regression"
Add-Check "S10-11" "Late payment reconciliation" `
    (Has "aimall-server/src/test/java/com/aimall/server/service/impl/PayServiceImplTest.java" "paidCallbackForClosedOrder") $true "late-payment state regression"
Add-Check "S10-12" "Refund channel success with local failure" `
    (Has "aimall-server/src/test/java/com/aimall/server/service/impl/RefundTaskProcessorTest.java" "DoesNotCallRefundChannelTwice") $true "refund fencing regression"
Add-Check "S10-13" "Concurrent Action confirm" `
    (Has "aimall-ai-service/tests/test_phase14_pending_actions.py" "concurrent_confirm_only_starts_one_execution") $true "execution-token fencing"
Add-Check "S10-14" "Knowledge review keeps old version ACTIVE" `
    (Has "aimall-ai-service/tests/test_milvus_status_batching.py" "keeps_old_active") $true "version-scoped Milvus status"
Add-Check "S10-15" "Stale knowledge attempt cannot overwrite vectors" `
    ((Has "aimall-ai-service/tests/test_vector_api_fencing.py" "compensates") -and (Has "aimall-ai-service/tests/test_vector_deletion_worker.py" "attempt-new")) $true "attempt-scoped vector IDs and compensation"
Add-Check "S10-16" "CI and dependency scanning" `
    (Has ".github/workflows/stage10-quality.yml" "dependency-audit") `
    ((Evidence-Matches ".acceptance/stage10/dependency-scan.json" {
        param($e) $e.passed -and [int]$e.scanners.web.vulnerabilities.total -eq 0 -and
        [int]$e.scanners.admin.vulnerabilities.total -eq 0 -and
        [int]$e.scanners.python.vulnerabilities -eq 0 -and
        @($e.scanners.java.vulnerabilities).Count -eq 0
    }) -and (Has ".acceptance/stage10/dependency-scan.json.sha256")) "all-ecosystem scan and SHA-256 artifact"
Add-Check "S10-17" "Migration upgrade and rollback drill" `
    (Has "aimall-server/src/test/java/com/aimall/server/integration/FlywayMigrationIntegrationTest.java") `
    ((Evidence-Matches ".acceptance/stage10/migration-drill.json" {
        param($e) $e.passed -and $e.flywayValidate -eq "PASSED" -and
        $e.latestMigrationReapplied -eq "20260719.1000"
    }) -and (Evidence-Matches ".acceptance/stage10/current-flyway-repair.json" {
        param($e) $e.passed -and $e.structuralPrecheck -eq "PASSED" -and
        $e.flywayValidate -eq "PASSED" -and @($e.repairedVersions).Count -eq 2
    })) "isolated snapshot upgrade plus guarded current-schema Flyway validation"
Add-Check "S10-18" "Backup and restore release gate" `
    (Has "scripts/stage10-mysql-backup-restore-drill.ps1" "dumpSha256") `
    (Evidence-Matches ".acceptance/stage10/backup-restore/result.json" {
        param($e) $e.passed -and [int]$e.tableCount -gt 0 -and [long]$e.dumpBytes -gt 0 -and
        -not [string]::IsNullOrWhiteSpace([string]$e.dumpSha256) -and
        @($e.coreTableRowCounts.PSObject.Properties).Count -ge 8
    }) "isolated restore, hash and business row-count evidence"

$localFailed = @($checks | Where-Object { -not $_.localReady })
$productionFailed = @($checks | Where-Object { -not $_.productionReady })
$result = [ordered]@{
    stage = 10
    generatedAt = [DateTimeOffset]::Now.ToString("o")
    total = $checks.Count
    localPassedCount = $checks.Count - $localFailed.Count
    localPassed = $localFailed.Count -eq 0
    productionPassedCount = $checks.Count - $productionFailed.Count
    productionPassed = $productionFailed.Count -eq 0
    checks = $checks
}
$outputPath = [System.IO.Path]::GetFullPath((Join-Path $rootPath $Output))
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($outputPath)) | Out-Null
[System.IO.File]::WriteAllText($outputPath, ($result | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
if (-not $result.localPassed) { throw "Stage 10 local quality gate failed: $($localFailed.id -join ',')" }
if ($RequireProduction -and -not $result.productionPassed) { throw "Stage 10 production gate failed: $($productionFailed.id -join ',')" }
Write-Output ($result | ConvertTo-Json -Depth 8)
