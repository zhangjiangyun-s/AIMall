param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"
$rootPath = [IO.Path]::GetFullPath($Root)
$restoreDatabase = "aimall_restore_stage10_" + (Get-Date -Format "yyyyMMddHHmmss")
$lines = Get-Content (Join-Path $rootPath ".env")
function Env-Value([string]$name) {
    $line = $lines | Where-Object { $_ -match "^$([regex]::Escape($name))=" } | Select-Object -First 1
    if (-not $line) { return $null }
    return ($line -split '=', 2)[1].Trim().Trim('"').Trim("'")
}
$rootPassword = Env-Value "MYSQL_ROOT_PASSWORD"
if ([string]::IsNullOrWhiteSpace($rootPassword)) { throw "MYSQL_ROOT_PASSWORD is required." }
$backupScript = Join-Path $rootPath "scripts/stage10-mysql-backup-restore-drill.ps1"
$mysql = (Get-Command mysql -ErrorAction Stop).Source
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $mavenCommand) { $mavenCommand = Get-Command mvn -ErrorAction Stop }
$maven = $mavenCommand.Source
$evidence = Join-Path $rootPath ".acceptance/stage10"

try {
    & $backupScript -SourceDatabase aimall -RestoreDatabase $restoreDatabase -Username root `
        -Password $rootPassword -KeepRestoreDatabase
    if ($LASTEXITCODE -ne 0) { throw "backup/restore drill failed." }
    $env:AIMALL_DB_USERNAME = "root"
    $env:AIMALL_DB_PASSWORD = $rootPassword
    $env:AIMALL_FLYWAY_DRILL_DATABASE = $restoreDatabase
    Push-Location (Join-Path $rootPath "aimall-server")
    & $maven -B '-Daimall.flyway.production-drill=true' '-Dtest=FlywayProductionSnapshotIntegrationTest' test
    if ($LASTEXITCODE -ne 0) { throw "production snapshot Flyway drill failed." }
    Pop-Location

    $result = [ordered]@{
        passed = $true
        generatedAt = [DateTimeOffset]::Now.ToString("o")
        sourceDatabase = "aimall"
        isolatedRestore = $restoreDatabase
        latestMigrationReapplied = "20260719.1000"
        flywayValidate = "PASSED"
        rollbackMethod = "restore original snapshot"
        restoreEvidence = ".acceptance/stage10/backup-restore/result.json"
    }
    $result | ConvertTo-Json | Set-Content (Join-Path $evidence "migration-drill.json") -Encoding UTF8
} finally {
    Pop-Location -ErrorAction SilentlyContinue
    Remove-Item Env:AIMALL_DB_USERNAME,Env:AIMALL_DB_PASSWORD,Env:AIMALL_FLYWAY_DRILL_DATABASE -ErrorAction SilentlyContinue
    $env:MYSQL_PWD = $rootPassword
    try { & $mysql -h 127.0.0.1 -P 3306 -u root -e "DROP DATABASE IF EXISTS ``$restoreDatabase``;" | Out-Null }
    finally { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
}
Write-Output (Join-Path $evidence "migration-drill.json")
