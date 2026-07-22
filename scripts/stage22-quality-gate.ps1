param(
    [switch]$RequireEngineering,
    [switch]$RequireProduction,
    [string]$Output = '.acceptance/stage22/quality-gate.json'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
function Read-Json([string]$path) { Get-Content -LiteralPath (Join-Path $root $path) -Raw -Encoding UTF8 | ConvertFrom-Json }
function Read-Utf8([string]$path) { [IO.File]::ReadAllText((Join-Path $root $path), [Text.Encoding]::UTF8) }
function Add-Check($checks, $id, $name, [bool]$engineering, [bool]$production, $evidence, $blocker) {
    $checks.Add([ordered]@{id=$id;name=$name;engineeringReady=$engineering;productionReady=$production;evidence=$evidence;productionBlocker=$blocker})
}

$python = Join-Path $root 'aimall-ai-service/.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { $python = (Get-Command python -ErrorAction Stop).Source }
& $python (Join-Path $root 'tools/stage22_e2e_evidence_gate.py') `
    --output (Join-Path $root '.acceptance/stage22/e2e-catalog-gate.json') | Out-Null

$catalog = Read-Json 'docs/operations/stage22-e2e-cases.json'
$catalogGate = Read-Json '.acceptance/stage22/e2e-catalog-gate.json'
$executionGate = Read-Json '.acceptance/stage22/e2e-execution-gate.json'
$securityPolicy = Read-Json 'docs/security/stage22-security-scan-policy.json'
$exceptions = Read-Json 'docs/security/stage22-security-exceptions.json'
$securityGate = Read-Json '.acceptance/stage22/security-gate.json'
$dependency = Read-Json '.acceptance/stage22/local-dependency-scan.json'
$verification = Read-Json '.acceptance/stage22/verification.json'
$injector = Read-Utf8 'tools/stage22_callback_injector.py'
$e2eTool = Read-Utf8 'tools/stage22_e2e_evidence_gate.py'
$securityTool = Read-Utf8 'tools/stage22_security_gate.py'
$tests = Read-Utf8 'aimall-ai-service/tests/test_stage22_e2e_security_gates.py'
$workflow = Read-Utf8 '.github/workflows/stage22-security.yml'
$runbook = Read-Utf8 'docs/operations/stage22-e2e-security-runbook.md'
$webLock = Read-Utf8 'aimall-web/package-lock.json'
$adminLock = Read-Utf8 'aimall-admin/package-lock.json'

$groupsComplete = $true
foreach ($case in $catalog.cases) {
    foreach ($group in @('business','database','event')) {
        if (@($case.assertions.$group).Count -eq 0) { $groupsComplete = $false }
    }
}
$requiredTools = @('semgrep','dependency-check-maven','audit --audit-level=high','pip-audit','gitleaks','checkov','trivy-action','action-baseline')
$workflowComplete = @($requiredTools | Where-Object { -not $workflow.Contains($_) }).Count -eq 0

$checks = [Collections.Generic.List[object]]::new()
Add-Check $checks 'S22-01' 'Ten-case E2E catalog with three assertion groups' `
    ([bool]$catalogGate.passed -and $catalog.cases.Count -eq 10 -and $groupsComplete -and $e2eTool.Contains('validate_execution')) `
    ([bool]$executionGate.passed -and $executionGate.executedCases -eq 10) `
    'All ten required domains have business, database and event/audit/ledger assertions; SIMULATE is forbidden.' `
    'Runtime evidence is 0/10; no sandbox or production-equivalent three-group E2E matrix has run.'
Add-Check $checks 'S22-02' 'Controlled delay, duplicate and out-of-order callback injection' `
    ($injector.Contains('rawBodyBase64') -and $injector.Contains('delayMs') -and $injector.Contains('repeat') -and
        $injector.Contains('sequence') -and $injector.Contains('allow_non_loopback') -and
        $tests.Contains('preserves_out_of_order_sequence_and_duplicates')) `
    (Test-Path (Join-Path $root '.acceptance/stage22/production/callback-faults.json')) `
    'Injector preserves raw signed payloads, supports delay/repeat/explicit ordering and defaults to loopback only.' `
    'No signed sandbox payment/refund and logistics callback fault run with database/event assertions exists.'
Add-Check $checks 'S22-03' 'CVSS 7 security gate and expiring exception protocol' `
    ($securityPolicy.requiredReports.Count -eq 9 -and $securityPolicy.blockCvssAtOrAbove -eq 7 -and
        $securityPolicy.exceptionRequiredFields.Count -eq 7 -and $exceptions.exceptions.Count -eq 0 -and
        $securityTool.Contains('active_exception') -and $tests.Contains('only_accepts_complete_unexpired_exception')) `
    ([bool]$securityGate.passed -and $securityGate.reportsSeen -eq 9) `
    'Nine normalized reports are required; missing/stale/wrong-revision reports and unwaived CVSS >=7 findings block.' `
    'Complete scanner evidence is 0/9; SAST, Maven, image, DAST, secret and IaC reports are absent.'
Add-Check $checks 'S22-04' 'Fixed scanner tooling and immutable scope' `
    ($workflowComplete -and $workflow.Contains('fetch-depth: 0') -and $runbook.Contains('same source revision')) `
    (Test-Path (Join-Path $root '.acceptance/stage22/production/security-workflow.json')) `
    'GitHub workflow fixes Semgrep, three dependency ecosystems, Trivy, ZAP, Gitleaks and Checkov scopes.' `
    'The complete workflow has not run against immutable release images and a deployed sandbox target.'
Add-Check $checks 'S22-05' 'Current dependency remediation and frontend regression' `
    ([bool]$dependency.passed -and -not [bool]$dependency.completeSecurityMatrix -and
        $dependency.results.python.vulnerabilities -eq 0 -and $dependency.results.web.vulnerabilities -eq 0 -and
        $dependency.results.admin.vulnerabilities -eq 0 -and $webLock.Contains('brace-expansion-2.1.2.tgz') -and
        $adminLock.Contains('brace-expansion-2.1.2.tgz') -and [bool]$verification.frontend.webBuild.passed -and
        [bool]$verification.frontend.adminBuild.passed -and [bool]$verification.frontend.playwrightMock.passed) `
    $false `
    'Current pip/npm audits are clean after fixing GHSA-3jxr-9vmj-r5cp; Web/Admin builds and mocked Playwright pass.' `
    'Dependency-only local scans and mocked UI cannot satisfy complete security or real E2E acceptance.'
Add-Check $checks 'S22-06' 'Full regression and fail-closed release evidence' `
    ([bool]$verification.python.passed -and [bool]$verification.java.passed -and
        [bool]$verification.realMySql.passed -and [bool]$verification.compose.passed -and
        $runbook.Contains('HTTP success alone is never sufficient')) `
    (Test-Path (Join-Path $root '.acceptance/stage22/production/signoff.json')) `
    'Python 344, Java 190, real MySQL 25, frontend and Compose regressions pass; incomplete production evidence remains closed.' `
    'No MySQL/Redis/LLM/Milvus full outage matrix and no QA/Security/DBA/SRE/Backend/business sign-off exists.'

$result=[ordered]@{stage=22;scope='E2E and security acceptance';generatedAt=[DateTimeOffset]::Now.ToString('o');total=$checks.Count;engineeringPassedCount=@($checks|Where-Object engineeringReady).Count;engineeringPassed=@($checks|Where-Object{-not $_.engineeringReady}).Count-eq 0;productionPassedCount=@($checks|Where-Object productionReady).Count;productionPassed=@($checks|Where-Object{-not $_.productionReady}).Count-eq 0;checks=$checks}
$outputPath=Join-Path $root $Output;[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputPath))|Out-Null;[IO.File]::WriteAllText($outputPath,($result|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false));$result|ConvertTo-Json -Depth 20
if(($RequireEngineering-and-not $result.engineeringPassed)-or($RequireProduction-and-not $result.productionPassed)){throw "Stage 22 quality gate failed: $outputPath"}
