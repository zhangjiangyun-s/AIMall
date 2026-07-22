param(
    [switch]$RequireEngineering,
    [switch]$RequireProduction,
    [string]$Output = '.acceptance/stage23/quality-gate.json'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$python = Join-Path $root 'aimall-ai-service/.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { $python = (Get-Command python -ErrorAction Stop).Source }
& $python (Join-Path $root 'tools/stage23_release_gate.py') --require-controls | Out-Null

function Read-Json([string]$path) { Get-Content -LiteralPath (Join-Path $root $path) -Raw -Encoding UTF8 | ConvertFrom-Json }
function Read-Utf8([string]$path) { [IO.File]::ReadAllText((Join-Path $root $path), [Text.Encoding]::UTF8) }
$decision = Read-Json '.acceptance/stage23/release-decision.json'
$policy = Read-Json 'docs/operations/stage23-release-gates.json'
$ledger = Read-Json 'docs/operations/stage23-release-blockers.json'
$signoff = Read-Json 'docs/operations/stage23-production-signoff.json'
$verification = Read-Json '.acceptance/stage23/verification.json'
$tests = Read-Utf8 'aimall-ai-service/tests/test_stage23_release_gate.py'

$checks = [Collections.Generic.List[object]]::new()
foreach ($gate in $decision.gates) {
    $policyGate = @($policy.gates | Where-Object id -eq $gate.id)[0]
    $gateBlockers = @($ledger.blockers | Where-Object { $_.gate -eq $gate.id -and $_.status -ne 'CLOSED' })
    $checks.Add([ordered]@{
        id = $gate.id
        name = $gate.name
        engineeringReady = [bool]$gate.controlImplemented
        productionReady = [bool]$gate.releasePassed
        owners = @($policyGate.owners)
        evidence = if ($gate.controlImplemented) { 'Ordered machine control implemented and current evidence evaluated.' } else { 'Control implementation missing.' }
        productionBlockers = @($gateBlockers | ForEach-Object { "$($_.id): $($_.summary)" })
    })
}
$ledgerValid = $ledger.blockers.Count -eq 9 -and @($ledger.blockers | Where-Object {
    [string]::IsNullOrWhiteSpace($_.owner) -or [string]::IsNullOrWhiteSpace($_.targetDate) -or
    [string]::IsNullOrWhiteSpace($_.requiredEvidence) -or $_.severity -notin @('P0','P1')
}).Count -eq 0
$signoffRoles = @($signoff.roles | ForEach-Object role | Sort-Object)
$expectedRoles = @('Backend','Business','DBA','QA','SRE','Security' | Sort-Object)
$structural = $policy.gates.Count -eq 7 -and $ledgerValid -and
    (($signoffRoles -join ',') -eq ($expectedRoles -join ',')) -and
    $tests.Contains('current_release_is_fail_closed') -and $tests.Contains('signoff_requires_six') -and
    [bool]$verification.python.passed

$result = [ordered]@{
    stage = 23
    scope = 'Ordered Gate 0 through Gate 6 release decision'
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    structuralPassed = $structural
    total = 7
    engineeringPassedCount = @($checks | Where-Object engineeringReady).Count
    engineeringPassed = $structural -and @($checks | Where-Object { -not $_.engineeringReady }).Count -eq 0
    productionPassedCount = @($checks | Where-Object productionReady).Count
    productionPassed = $structural -and @($checks | Where-Object { -not $_.productionReady }).Count -eq 0
    releaseDecision = $decision.decision
    openP0P1Count = $decision.openP0P1Count
    signoffsComplete = $decision.signoffsComplete
    evidenceManifestSha256 = $decision.evidenceManifestSha256
    checks = $checks
}
$outputPath=Join-Path $root $Output;[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputPath))|Out-Null;[IO.File]::WriteAllText($outputPath,($result|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false));$result|ConvertTo-Json -Depth 20
if(($RequireEngineering -and -not $result.engineeringPassed)-or($RequireProduction -and -not $result.productionPassed)){throw "Stage 23 quality gate failed: $outputPath"}
