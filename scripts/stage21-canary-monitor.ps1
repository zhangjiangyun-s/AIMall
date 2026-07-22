param(
    [string]$PrometheusBaseUrl = 'http://127.0.0.1:9090',
    [Parameter(Mandatory = $true)][string]$Tenant,
    [Parameter(Mandatory = $true)][string]$User,
    [string]$Policy = 'docs/operations/canary-release-policy.json',
    [string]$Output = '.acceptance/stage21/canary-decision.json',
    [string]$MetricsOutput = '.acceptance/stage21/canary-metrics.json',
    [string]$Python = 'python',
    [int]$IntervalSeconds = 30,
    [switch]$Once
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

function Query-Prometheus([string]$query) {
    $uri = $PrometheusBaseUrl.TrimEnd('/') + '/api/v1/query?query=' + [Uri]::EscapeDataString($query)
    $response = Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec 10
    if ($response.status -ne 'success' -or @($response.data.result).Count -ne 1) {
        throw "Prometheus query returned no unique sample: $query"
    }
    return [double]$response.data.result[0].value[1]
}

function Invoke-Decision {
    $metrics = [ordered]@{}
    $queries = [ordered]@{
        paymentDifferenceCount = 'sum(aimall_payment_unknown) + sum(aimall_payment_reconciliation_open)'
        inventoryInvalidCount = 'sum(aimall_inventory_invalid)'
        tenantLeakageCount = 'sum(aimall_release_tenant_leakage)'
        aiCitationErrorRate = 'sum(rate(aimall_ai_rag_citation_errors_total[5m])) / clamp_min(sum(rate(aimall_ai_rag_queries_total[5m])), 1)'
        errorRate = 'sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / clamp_min(sum(rate(http_server_requests_seconds_count[5m])), 1)'
    }
    foreach ($item in $queries.GetEnumerator()) {
        try {
            $metrics[$item.Key] = Query-Prometheus $item.Value
        } catch {
            # Missing data is intentionally omitted; the canary gate treats it as a trip condition.
        }
    }
    $metricsPath = [IO.Path]::GetFullPath((Join-Path $root $MetricsOutput))
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($metricsPath)) | Out-Null
    [IO.File]::WriteAllText($metricsPath, ($metrics | ConvertTo-Json), [Text.UTF8Encoding]::new($false))

    & $Python (Join-Path $root 'tools/stage21_canary_gate.py') `
        --policy (Join-Path $root $Policy) --metrics $metricsPath --tenant $Tenant --user $User `
        --output (Join-Path $root $Output)
    return $LASTEXITCODE
}

do {
    $exitCode = Invoke-Decision
    if ($Once) { exit $exitCode }
    Start-Sleep -Seconds ([Math]::Max(5, $IntervalSeconds))
} while ($true)
