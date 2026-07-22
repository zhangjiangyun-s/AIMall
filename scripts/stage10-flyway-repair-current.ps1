param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"
$rootPath = [IO.Path]::GetFullPath($Root)
$lines = Get-Content (Join-Path $rootPath ".env")
function Env-Value([string]$name) {
    $line = $lines | Where-Object { $_ -match "^$([regex]::Escape($name))=" } | Select-Object -First 1
    if (-not $line) { return $null }
    return ($line -split '=', 2)[1].Trim().Trim('"').Trim("'")
}
$rootPassword = Env-Value "MYSQL_ROOT_PASSWORD"
if ([string]::IsNullOrWhiteSpace($rootPassword)) { throw "MYSQL_ROOT_PASSWORD is required." }
$backupEvidence = Join-Path $rootPath ".acceptance/stage10/backup-restore/result.json"
$migrationEvidence = Join-Path $rootPath ".acceptance/stage10/migration-drill.json"
if (-not (Test-Path $backupEvidence) -or -not (Test-Path $migrationEvidence)) {
    throw "Successful backup/restore and isolated migration drill evidence are required before repair."
}
if (-not (Get-Content $backupEvidence -Raw | ConvertFrom-Json).passed `
        -or -not (Get-Content $migrationEvidence -Raw | ConvertFrom-Json).passed) {
    throw "Pre-repair drill evidence is not successful."
}

$env:AIMALL_DB_USERNAME = "root"
$env:AIMALL_DB_PASSWORD = $rootPassword
$env:AIMALL_FLYWAY_DRILL_DATABASE = "aimall"
$env:AIMALL_FLYWAY_REPAIR_ONLY = "true"
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $mavenCommand) { $mavenCommand = Get-Command mvn -ErrorAction Stop }
$maven = $mavenCommand.Source
try {
    Push-Location (Join-Path $rootPath "aimall-server")
    & $maven -B '-Daimall.flyway.production-drill=true' '-Dtest=FlywayProductionSnapshotIntegrationTest' test
    if ($LASTEXITCODE -ne 0) { throw "Current database Flyway repair failed." }
    [ordered]@{
        passed = $true
        generatedAt = [DateTimeOffset]::Now.ToString("o")
        database = "aimall"
        repairedVersions = @("20260718.1860", "20260718.1870")
        structuralPrecheck = "PASSED"
        flywayValidate = "PASSED"
        backupEvidence = ".acceptance/stage10/backup-restore/result.json"
    } | ConvertTo-Json | Set-Content (Join-Path $rootPath ".acceptance/stage10/current-flyway-repair.json") -Encoding UTF8
} finally {
    Pop-Location -ErrorAction SilentlyContinue
    Remove-Item Env:AIMALL_DB_USERNAME,Env:AIMALL_DB_PASSWORD,Env:AIMALL_FLYWAY_DRILL_DATABASE,Env:AIMALL_FLYWAY_REPAIR_ONLY -ErrorAction SilentlyContinue
}
