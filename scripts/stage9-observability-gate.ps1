param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Output = ".acceptance/stage9/observability-gate.json"
)

$ErrorActionPreference = "Stop"
$rootPath = [System.IO.Path]::GetFullPath($Root)
$checks = [System.Collections.Generic.List[object]]::new()

function Add-Check([string]$id, [bool]$passed, [string]$detail) {
    $checks.Add([ordered]@{ id = $id; passed = $passed; detail = $detail })
}

function Read-Utf8([string]$relative) {
    return [System.IO.File]::ReadAllText(
        (Join-Path $rootPath $relative),
        [System.Text.UTF8Encoding]::new($false, $true)
    )
}

$pom = Read-Utf8 "aimall-server/pom.xml"
$application = Read-Utf8 "aimall-server/src/main/resources/application.yml"
$saToken = Read-Utf8 "aimall-server/src/main/java/com/aimall/server/config/SaTokenConfigure.java"
$signing = Read-Utf8 "aimall-server/src/main/java/com/aimall/server/config/AiServiceRequestSigningInterceptor.java"
$outbox = Read-Utf8 "aimall-server/src/main/java/com/aimall/server/service/impl/OutboxEventService.java"
$main = Read-Utf8 "aimall-ai-service/main.py"
$javaClient = Read-Utf8 "aimall-ai-service/app/tools/java_client.py"
$alerts = Read-Utf8 "docker/observability/alerts.yml"
$runbook = Read-Utf8 "docs/AIMALL_STAGE9_OBSERVABILITY_RUNBOOK.md"

Add-Check "S9-ACTUATOR" ($pom.Contains("spring-boot-starter-actuator") -and $pom.Contains("micrometer-registry-prometheus")) "Java Actuator and Prometheus registry"
Add-Check "S9-HTTP-PERCENTILES" ($application.Contains("0.5,0.95,0.99") -and $application.Contains("percentiles-histogram")) "HTTP P50/P95/P99 histogram"
Add-Check "S9-PROM-AUTH" ($saToken.Contains("/actuator/prometheus") -and (Test-Path (Join-Path $rootPath "aimall-server/src/main/java/com/aimall/server/config/ObservabilityAuthFilter.java"))) "Dedicated metrics token"
Add-Check "S9-JAVA-TRACE" ($signing.Contains("X-Trace-Id") -and $outbox.Contains('MDC.get("traceId")')) "Java-to-AI and Outbox trace propagation"
Add-Check "S9-AI-TRACE" ($main.Contains("TraceContextMiddleware") -and $javaClient.Contains("X-Trace-Id")) "AI-to-Java trace propagation"
Add-Check "S9-STRUCTURED-LOGS" ((Test-Path (Join-Path $rootPath "aimall-server/src/main/resources/logback-spring.xml")) -and $main.Contains("configure_logging")) "JSON stdout logging"
Add-Check "S9-AI-METRICS" (Test-Path (Join-Path $rootPath "aimall-ai-service/app/api/metrics_api.py")) "AI Prometheus endpoint"
Add-Check "S9-RETENTION" ($application.Contains("audit-retention-days") -and $runbook.Contains("180")) "Runtime and audit retention policy"

$alertBlocks = [regex]::Split($alerts, "(?m)^\s*- alert: ") | Select-Object -Skip 1
$completeAlerts = 0
foreach ($block in $alertBlocks) {
    if ($block.Contains("owner:") -and $block.Contains("impact:") -and $block.Contains("recovery:") -and $block.Contains("escalation:") -and $block.Contains("runbook_url:")) {
        $completeAlerts++
    }
}
Add-Check "S9-ALERT-METADATA" ($alertBlocks.Count -ge 10 -and $completeAlerts -eq $alertBlocks.Count) ("alerts=" + $alertBlocks.Count + ", complete=" + $completeAlerts)
Add-Check "S9-RUNBOOK" ($runbook.Contains("Payment Inconsistent") -and $runbook.Contains("Database Or Migration") -and $runbook.Contains("Milvus Inconsistent")) "P0/P1 recovery runbooks"

$failed = @($checks | Where-Object { -not $_.passed })
$result = [ordered]@{
    gate = "stage9-observability"
    generatedAt = [DateTimeOffset]::Now.ToString("o")
    total = $checks.Count
    passedCount = $checks.Count - $failed.Count
    failedCount = $failed.Count
    passed = $failed.Count -eq 0
    checks = $checks
}
$outputPath = [System.IO.Path]::GetFullPath((Join-Path $rootPath $Output))
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($outputPath)) | Out-Null
[System.IO.File]::WriteAllText($outputPath, ($result | ConvertTo-Json -Depth 8), [System.Text.UTF8Encoding]::new($false))
if (-not $result.passed) { Write-Error "Stage 9 observability gate failed: $outputPath" }
Write-Output ($result | ConvertTo-Json -Depth 8)
