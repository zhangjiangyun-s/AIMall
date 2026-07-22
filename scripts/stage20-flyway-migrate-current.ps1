param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Maven,
    [switch]$ApproveCurrentDatabaseMigration
)

$ErrorActionPreference = 'Stop'
if (-not $ApproveCurrentDatabaseMigration) { throw 'ApproveCurrentDatabaseMigration is required' }
if ($Maven) {
    $Maven = (Get-Item -LiteralPath $Maven -ErrorAction Stop).FullName
} else {
    $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $mavenCommand) { $mavenCommand = Get-Command mvn -ErrorAction Stop }
    $Maven = $mavenCommand.Source
}
$rootPath = [IO.Path]::GetFullPath($Root)
$evidencePath = Join-Path $rootPath '.acceptance/stage20/backup-restore/result.json'
if (-not (Test-Path $evidencePath)) { throw 'Stage 20 encrypted restore evidence is required' }
$evidence = Get-Content $evidencePath -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $evidence.passed -or -not $evidence.rtoPassed -or $evidence.latestFlywayVersion -ne '20260722.0900') {
    throw 'Stage 20 isolated restore and Flyway evidence is not successful'
}
$encrypted = Join-Path $rootPath $evidence.encryptedBackup
if (-not (Test-Path $encrypted)) { throw 'Encrypted backup referenced by evidence is missing' }
if ((Get-FileHash $encrypted -Algorithm SHA256).Hash -ne $evidence.encryptedBackupSha256) {
    throw 'Encrypted backup hash does not match recovery evidence'
}

foreach ($line in Get-Content (Join-Path $rootPath '.env') -Encoding UTF8) {
    $value = $line.Trim()
    if (-not $value -or $value.StartsWith('#') -or -not $value.Contains('=')) { continue }
    $parts = $value.Split('=', 2)
    if (-not [Environment]::GetEnvironmentVariable($parts[0].Trim(), 'Process')) {
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim().Trim('"').Trim("'"), 'Process')
    }
}
if ([string]::IsNullOrWhiteSpace($env:MYSQL_ROOT_PASSWORD)) { throw 'MYSQL_ROOT_PASSWORD is required' }
$env:AIMALL_DB_USERNAME = 'root'
$env:AIMALL_DB_PASSWORD = $env:MYSQL_ROOT_PASSWORD
$env:AIMALL_FLYWAY_DRILL_DATABASE = 'aimall'
$started = [DateTimeOffset]::UtcNow
try {
    Push-Location (Join-Path $rootPath 'aimall-server')
    & $Maven -B '-Daimall.flyway.current-migrate=true' '-Dtest=FlywayCurrentDatabaseMigrationIntegrationTest' test
    if ($LASTEXITCODE -ne 0) { throw 'current database Flyway migration failed' }
} finally {
    Pop-Location -ErrorAction SilentlyContinue
    Remove-Item Env:AIMALL_FLYWAY_DRILL_DATABASE -ErrorAction SilentlyContinue
}
$result = [ordered]@{
    stage = 20
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    database = 'aimall'
    backupRunId = $evidence.runId
    backupSha256 = $evidence.encryptedBackupSha256
    latestFlywayVersion = '20260722.0900'
    durationSeconds = [Math]::Round(([DateTimeOffset]::UtcNow - $started).TotalSeconds, 3)
    passed = $true
}
$output = Join-Path $rootPath '.acceptance/stage20/current-flyway-migration.json'
[IO.File]::WriteAllText($output, ($result | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
$result | ConvertTo-Json -Depth 6
