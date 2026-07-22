param(
    [switch]$RequireEngineering,
    [switch]$RequireProduction,
    [string]$Output = '.acceptance/stage21/quality-gate.json'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Read-Json([string]$relativePath) {
    return Get-Content -LiteralPath (Join-Path $root $relativePath) -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Read-Utf8([string]$relativePath) {
    return [IO.File]::ReadAllText((Join-Path $root $relativePath), [Text.Encoding]::UTF8)
}

function Production-Passed([string]$relativePath) {
    $path = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $false }
    try {
        $evidence = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
        return [bool]$evidence.passed -and $evidence.environment -eq 'production-equivalent'
    } catch { return $false }
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

$capacity = Read-Json 'docs/operations/capacity-gates.json'
$canary = Read-Json 'docs/operations/canary-release-policy.json'
$verification = Read-Json '.acceptance/stage21/verification.json'
$runner = Read-Utf8 'tools/stage21_capacity_runner.py'
$canaryTool = Read-Utf8 'tools/stage21_canary_gate.py'
$monitor = Read-Utf8 'scripts/stage21-canary-monitor.ps1'
$alerts = Read-Utf8 'docker/observability/alerts.yml'
$settings = Read-Utf8 'aimall-ai-service/app/config/settings.py'
$redisBackend = Read-Utf8 'aimall-ai-service/app/state/redis_backend.py'
$metricsApi = Read-Utf8 'aimall-ai-service/app/api/metrics_api.py'
$application = Read-Utf8 'aimall-server/src/main/resources/application.yml'
$javaMetrics = Read-Utf8 'aimall-server/src/main/java/com/aimall/server/observability/OperationalMetricsService.java'
$tests = Read-Utf8 'aimall-ai-service/tests/test_stage21_capacity_release.py'
$runbook = Read-Utf8 'docs/operations/performance-capacity-release.md'

$scenarioIds = @($capacity.scenarios | ForEach-Object id)
$metadataFields = @($capacity.metadataRequired)
$capacityContractReady = $capacity.schemaVersion -eq 'AIMALL_CAPACITY_GATES_V1' -and
    $capacity.scenarios.Count -eq 8 -and
    @('LOGIN_PEAK','ORDER_CREATE_PEAK','PAYMENT_CALLBACK_PEAK','AI_REQUEST_CONCURRENCY','SSE_CONNECTIONS','REDIS_POOL_UTILIZATION','MYSQL_POOL_AND_LOCK_WAIT','MILVUS_RETRIEVAL' | Where-Object { $_ -notin $scenarioIds }).Count -eq 0 -and
    @('hardware','dataVolume','model','cacheHitRate','concurrencyModel' | Where-Object { $_ -notin $metadataFields }).Count -eq 0

$alertCount = ([regex]::Matches($alerts, '(?m)^\s+- alert: AIMall(LoginP95|OrderLatency|PaymentCallbackP95|JavaPool|RedisPool|MySqlLockWait|AiOrMilvusLatency|ReleaseErrorRate)')).Count
$checks = [Collections.Generic.List[object]]::new()
Add-Check $checks 'S21-01' 'Versioned capacity contract and evidence metadata' `
    $capacityContractReady `
    (Production-Passed '.acceptance/stage21/production/login-capacity.json') `
    'Eight initial scenarios and mandatory hardware/data/model/cache/concurrency metadata are versioned.' `
    'No production-equivalent 100 RPS login run with complete metadata exists.'
Add-Check $checks 'S21-02' 'Safe repeatable HTTP/SSE load runner' `
    ($runner.Contains('ThreadPoolExecutor') -and $runner.Contains('approve_write_scenarios') -and
        $runner.Contains('metadataComplete') -and $runner.Contains('{{requestId}}') -and
        $tests.Contains('refuses_unapproved_write_scenario') -and [bool]$verification.targeted.passed) `
    ((Production-Passed '.acceptance/stage21/production/order-capacity.json') -and
        (Production-Passed '.acceptance/stage21/production/payment-callback-capacity.json')) `
    'Runner performs warmup and measured concurrency, enforces thresholds, records metadata and refuses unapproved writes.' `
    'No production-equivalent 30 RPS order and 100 RPS signed payment callback runs exist.'
Add-Check $checks 'S21-03' 'Pool, lock, HTTP and RAG performance metrics' `
    ($redisBackend.Contains('poolUtilization') -and $metricsApi.Contains('pool_utilization') -and
        $javaMetrics.Contains('aimall_mysql_row_lock_time_max_ms') -and
        $application.Contains('http.server.requests: 0.5,0.95,0.99') -and $alertCount -eq 8) `
    (Production-Passed '.acceptance/stage21/production/runtime-metrics.json') `
    'Prometheus covers HTTP percentiles, Hikari/Redis pools, conservative MySQL lock waits and AI/Milvus latency.' `
    'No sustained production-topology metric capture proves pool, lock and retrieval thresholds.'
Add-Check $checks 'S21-04' 'AI overload and SSE concurrency boundaries' `
    ($settings.Contains('SSE_MAX_GLOBAL_CONNECTIONS: int = int(os.getenv("SSE_MAX_GLOBAL_CONNECTIONS", "200"))') -and
        $settings.Contains('REDIS_MAX_CONNECTIONS') -and $application.Contains('AIMALL_DB_POOL_MAX_SIZE:50') -and
        $scenarioIds -contains 'AI_REQUEST_CONCURRENCY' -and $scenarioIds -contains 'SSE_CONNECTIONS') `
    ((Production-Passed '.acceptance/stage21/production/ai-capacity.json') -and
        (Production-Passed '.acceptance/stage21/production/sse-capacity.json')) `
    'Defaults provide 200 SSE connections per instance, per-client limits and bounded Redis/MySQL pools.' `
    'No 20-concurrent real-model AI run or 200-connection multi-instance SSE soak exists.'
Add-Check $checks 'S21-05' 'Tenant/user canary and persistent automatic trip' `
    ($canary.schemaVersion -eq 'AIMALL_CANARY_POLICY_V1' -and $canary.requireTenantAndUserMatch -and
        -not $canary.tripBehavior.automaticReopen -and $canary.tripBehavior.failClosed -and
        $canaryTool.Contains('os.fsync') -and $canaryTool.Contains('PERSISTED_TRIP') -and
        $monitor.Contains('MISSING_METRIC') -eq $false -and $monitor.Contains('aimall_release_tenant_leakage') -and
        $tests.Contains('persists_threshold_trip') -and $tests.Contains('missing_metric_fails_closed')) `
    (Production-Passed '.acceptance/stage21/production/canary-rollback-drill.json') `
    'Token-derived tenant/user eligibility is required operationally; missing/violating metrics persistently trip and require change-ID reset.' `
    'Allowlists are intentionally empty and no real traffic canary, independent leakage synthetic or rollback drill exists.'
Add-Check $checks 'S21-06' 'Regression, runbook and release evidence gate' `
    ([bool]$verification.python.passed -and [bool]$verification.java.passed -and
        [bool]$verification.realMySql.passed -and [bool]$verification.compose.passed -and
        $runbook.Contains('Automatic Trip') -and $runbook.Contains('production-sized data')) `
    (Production-Passed '.acceptance/stage21/production/signoff.json') `
    'Python 340, Java 189 and real MySQL 25 tests pass; capacity/canary/rollback procedures are documented.' `
    'No production-sized report, on-call rollback exercise or SRE/DBA/Security/QA/business sign-off exists.'

$result = [ordered]@{
    stage = 21
    scope = 'Performance, capacity and release gates'
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
if (($RequireEngineering -and -not $result.engineeringPassed) -or ($RequireProduction -and -not $result.productionPassed)) {
    throw "Stage 21 quality gate failed: $outputPath"
}
