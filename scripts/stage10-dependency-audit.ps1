param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"
$rootPath = [IO.Path]::GetFullPath($Root)
$npmCommand = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $npmCommand) { $npmCommand = Get-Command npm -ErrorAction Stop }
$npm = $npmCommand.Source
$python = Join-Path $rootPath "aimall-ai-service/.venv/Scripts/python.exe"
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    $python = (Get-Command python -ErrorAction Stop).Source
}
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $mavenCommand) { $mavenCommand = Get-Command mvn -ErrorAction Stop }
$maven = $mavenCommand.Source
$evidenceDir = Join-Path $rootPath ".acceptance/stage10"
New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null

function Npm-Audit([string]$directory) {
    Push-Location $directory
    try {
        $raw = (& $npm audit --json 2>$null) -join "`n"
        $report = $raw | ConvertFrom-Json
        return [ordered]@{
            total = [int]$report.metadata.vulnerabilities.total
            high = [int]$report.metadata.vulnerabilities.high
            critical = [int]$report.metadata.vulnerabilities.critical
        }
    } finally { Pop-Location }
}

$web = Npm-Audit (Join-Path $rootPath "aimall-web")
$admin = Npm-Audit (Join-Path $rootPath "aimall-admin")

Push-Location (Join-Path $rootPath "aimall-ai-service")
try {
    $previousErrorPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $pythonRaw = (& $python -m pip_audit -r requirements.txt --format json 2>$null) -join "`n"
    $ErrorActionPreference = $previousErrorPreference
    if ($LASTEXITCODE -ne 0) { throw "pip-audit failed." }
    $pythonReport = $pythonRaw | ConvertFrom-Json
} finally {
    $ErrorActionPreference = "Stop"
    Pop-Location
}
$pythonVulnerabilities = @($pythonReport.dependencies | ForEach-Object { @($_.vulns) }).Count

$dependencyFile = Join-Path $evidenceDir "maven-runtime-dependencies.txt"
Push-Location (Join-Path $rootPath "aimall-server")
try {
    & $maven -B dependency:list '-DincludeScope=runtime' "-DoutputFile=$dependencyFile" '-DappendOutput=false' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Maven dependency:list failed." }
} finally { Pop-Location }

$coordinates = [System.Collections.Generic.List[object]]::new()
foreach ($line in Get-Content $dependencyFile) {
    $trimmed = $line.Trim()
    if ($trimmed -match '^([^: ]+):([^: ]+):[^: ]+:([^: ]+):(compile|runtime)(?:\s|$)') {
        $coordinates.Add([ordered]@{ name = "$($Matches[1]):$($Matches[2])"; version = $Matches[3] })
    }
}
if ($coordinates.Count -eq 0) { throw "No Maven runtime coordinates were parsed." }

$javaVulnerabilities = [System.Collections.Generic.List[object]]::new()
for ($offset = 0; $offset -lt $coordinates.Count; $offset += 100) {
    $upper = [Math]::Min($coordinates.Count - 1, $offset + 99)
    $batch = @($coordinates[$offset..$upper])
    $payload = @{ queries = @($batch | ForEach-Object {
        @{ package = @{ ecosystem = "Maven"; name = $_.name }; version = $_.version }
    }) } | ConvertTo-Json -Depth 8 -Compress
    $response = Invoke-RestMethod -Method Post -Uri "https://api.osv.dev/v1/querybatch" `
        -ContentType "application/json" -Body ([Text.Encoding]::UTF8.GetBytes($payload))
    for ($index = 0; $index -lt $batch.Count; $index++) {
        foreach ($vulnerability in @($response.results[$index].vulns)) {
            if ($null -ne $vulnerability) {
                $javaVulnerabilities.Add([ordered]@{
                    package = $batch[$index].name
                    version = $batch[$index].version
                    id = $vulnerability.id
                    aliases = @($vulnerability.aliases)
                })
            }
        }
    }
}

$passed = $web.total -eq 0 -and $admin.total -eq 0 -and $pythonVulnerabilities -eq 0 -and $javaVulnerabilities.Count -eq 0
$result = [ordered]@{
    passed = $passed
    generatedAt = [DateTimeOffset]::Now.ToString("o")
    scanners = [ordered]@{
        web = [ordered]@{ scanner = "npm audit"; vulnerabilities = $web }
        admin = [ordered]@{ scanner = "npm audit"; vulnerabilities = $admin }
        python = [ordered]@{ scanner = "pip-audit"; dependencies = @($pythonReport.dependencies).Count; vulnerabilities = $pythonVulnerabilities }
        java = [ordered]@{ scanner = "OSV Batch API"; dependencies = $coordinates.Count; vulnerabilities = @($javaVulnerabilities) }
    }
}
$output = Join-Path $evidenceDir "dependency-scan.json"
[IO.File]::WriteAllText($output, ($result | ConvertTo-Json -Depth 10), [Text.UTF8Encoding]::new($false))
$hash = (Get-FileHash $output -Algorithm SHA256).Hash
[IO.File]::WriteAllText("$output.sha256", "$hash  dependency-scan.json`n", [Text.UTF8Encoding]::new($false))
if (-not $passed) { throw "Dependency audit found vulnerabilities. See $output" }
Write-Output $output
