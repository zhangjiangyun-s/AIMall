param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Output = ".acceptance/stage16/key-ledger-gate.json",
    [switch]$RequireAllEngineering,
    [switch]$RequireProduction
)
$ErrorActionPreference = "Stop"
$rootPath = [IO.Path]::GetFullPath($Root)
$ledger = Get-Content (Join-Path $rootPath ".acceptance/stage16/key-ledger.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$verification = Get-Content (Join-Path $rootPath ".acceptance/stage16/verification.json") -Raw -Encoding UTF8 | ConvertFrom-Json
$expected = @("CORE-001","CORE-002","CORE-003","CONF-001","CONF-002","CONF-003","PAY-001","PAY-002","PAY-003","INV-001","INV-002","COUPON-001","TENANT-001","TENANT-002","TRACE-001","TRACE-002","HEALTH-001")
$items = @($ledger.items)
$ids = @($items.id)
$allowed = @("ACCEPTED","IN_PROGRESS","BLOCKED")
$duplicate = @($ids | Group-Object | Where-Object Count -gt 1)
$missing = @($expected | Where-Object { $_ -notin $ids })
$unknown = @($ids | Where-Object { $_ -notin $expected })
$invalid = @($items | Where-Object {
    $_.engineering -notin $allowed -or $_.production -notin $allowed -or
    -not $_.priority -or @($_.evidence).Count -eq 0 -or -not $_.gap
})
$engineeringAccepted = @($items | Where-Object engineering -eq "ACCEPTED")
$productionAccepted = @($items | Where-Object production -eq "ACCEPTED")
$structural = $items.Count -eq 17 -and $duplicate.Count -eq 0 -and $missing.Count -eq 0 -and $unknown.Count -eq 0 -and $invalid.Count -eq 0
$result = [ordered]@{
    stage = 16
    source = "AIMALL_ENGINEERING_PRODUCTION_REMEDIATION_PLAN.md section 16"
    generatedAt = [DateTimeOffset]::Now.ToString("o")
    total = 17
    structurallyPassed = $structural
    engineeringAcceptedCount = $engineeringAccepted.Count
    engineeringFullyCompleted = $engineeringAccepted.Count -eq 17
    productionAcceptedCount = $productionAccepted.Count
    productionReady = $productionAccepted.Count -eq 17
    verification = [ordered]@{
        javaPassed = $verification.java.passed
        pythonPassed = $verification.python.passed
        composeConfig = $verification.composeConfig
        realMySqlPassed = $verification.realMySql.passed
    }
    engineeringOpen = @($items | Where-Object engineering -ne "ACCEPTED" | ForEach-Object id)
    productionOpen = @($items | Where-Object production -ne "ACCEPTED" | ForEach-Object id)
}
$outputPath = [IO.Path]::GetFullPath((Join-Path $rootPath $Output))
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputPath)) | Out-Null
[IO.File]::WriteAllText($outputPath, ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
if (-not $structural) { throw "Stage 16 ledger structure failed" }
if ($RequireAllEngineering -and -not $result.engineeringFullyCompleted) { throw "Stage 16 engineering remains open: $($result.engineeringOpen -join ',')" }
if ($RequireProduction -and -not $result.productionReady) { throw "Stage 16 production remains open" }
$result | ConvertTo-Json -Depth 8
