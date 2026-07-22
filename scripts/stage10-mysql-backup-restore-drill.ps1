param(
    [Parameter(Mandatory = $true)][string]$SourceDatabase,
    [Parameter(Mandatory = $true)][string]$RestoreDatabase,
    [string]$HostName = "127.0.0.1",
    [int]$Port = 3306,
    [string]$Username = $env:AIMALL_DB_USERNAME,
    [string]$Password = $env:AIMALL_DB_PASSWORD,
    [string]$EvidenceDirectory = ".acceptance/stage10/backup-restore",
    [switch]$KeepRestoreDatabase
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($Username) -or [string]::IsNullOrWhiteSpace($Password)) {
    throw "AIMALL_DB_USERNAME and AIMALL_DB_PASSWORD are required."
}
if ($SourceDatabase -eq $RestoreDatabase -or $RestoreDatabase -notmatch '^aimall_restore_[a-zA-Z0-9_]+$') {
    throw "RestoreDatabase must be an isolated aimall_restore_* database."
}
$dump = (Get-Command mysqldump -ErrorAction Stop).Source
$mysql = (Get-Command mysql -ErrorAction Stop).Source
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$evidence = [System.IO.Path]::GetFullPath((Join-Path $root $EvidenceDirectory))
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$dumpFile = Join-Path $evidence ("{0}-{1}.sql" -f $SourceDatabase, (Get-Date -Format "yyyyMMddHHmmss"))
$env:MYSQL_PWD = $Password
$coreTables = @("ums_member", "pms_product", "pms_sku_stock", "oms_order", "oms_order_item", "oms_payment_record", "oms_refund_record", "outbox_event")
function Row-Count([string]$database, [string]$table) {
    $value = & $mysql --host=$HostName --port=$Port --user=$Username --batch --skip-column-names `
        --execute="SELECT COUNT(*) FROM ``$database``.``$table``;"
    if ($LASTEXITCODE -ne 0) { throw "row count failed for $database.$table" }
    return [long]$value
}
$sourceCounts = [ordered]@{}
foreach ($table in $coreTables) { $sourceCounts[$table] = Row-Count $SourceDatabase $table }
try {
    & $dump --host=$HostName --port=$Port --user=$Username --single-transaction --routines --events `
        --set-gtid-purged=OFF --result-file=$dumpFile $SourceDatabase
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $dumpFile)) { throw "mysqldump failed." }
    & $mysql --host=$HostName --port=$Port --user=$Username --execute="DROP DATABASE IF EXISTS ``$RestoreDatabase``; CREATE DATABASE ``$RestoreDatabase`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    if ($LASTEXITCODE -ne 0) { throw "restore database creation failed." }
    $restore = Start-Process -FilePath $mysql -ArgumentList @(
        "--host=$HostName", "--port=$Port", "--user=$Username", $RestoreDatabase
    ) -RedirectStandardInput $dumpFile -Wait -NoNewWindow -PassThru
    if ($restore.ExitCode -ne 0) { throw "restore import failed." }
    $tables = & $mysql --host=$HostName --port=$Port --user=$Username --batch --skip-column-names `
        --execute="SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$RestoreDatabase';"
    if ([int]$tables -le 0) { throw "restored database has no tables." }
    $restoreCounts = [ordered]@{}
    foreach ($table in $coreTables) {
        $restoreCounts[$table] = Row-Count $RestoreDatabase $table
        if ($restoreCounts[$table] -ne $sourceCounts[$table]) {
            throw "row count mismatch for ${table}: source=$($sourceCounts[$table]), restore=$($restoreCounts[$table])"
        }
    }
    [ordered]@{
        completedAt = [DateTimeOffset]::Now.ToString("o")
        sourceDatabase = $SourceDatabase
        restoreDatabase = $RestoreDatabase
        dumpSha256 = (Get-FileHash $dumpFile -Algorithm SHA256).Hash
        tableCount = [int]$tables
        dumpBytes = (Get-Item $dumpFile).Length
        coreTableRowCounts = $restoreCounts
        passed = $true
    } | ConvertTo-Json | Set-Content (Join-Path $evidence "result.json") -Encoding UTF8
} finally {
    if (-not $KeepRestoreDatabase) {
        & $mysql --host=$HostName --port=$Port --user=$Username `
            --execute="DROP DATABASE IF EXISTS ``$RestoreDatabase``;" | Out-Null
    }
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
