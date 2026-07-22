param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$SourceDatabase = 'aimall',
    [string]$RestoreDatabase = ('aimall_restore_stage20_' + (Get-Date -Format 'yyyyMMddHHmmss')),
    [string]$HostName = '127.0.0.1',
    [int]$Port = 3306,
    [string]$AdminUsername = 'root',
    [string]$AdminPassword = $env:MYSQL_ROOT_PASSWORD,
    [string]$BackupUsername = $env:AIMALL_BACKUP_DB_USERNAME,
    [string]$BackupPassword = $env:AIMALL_BACKUP_DB_PASSWORD,
    [string]$EncryptionKey = $env:AIMALL_BACKUP_ENCRYPTION_KEY,
    [string]$EvidenceDirectory = '.acceptance/stage20/backup-restore',
    [string]$Maven,
    [switch]$ProvisionEphemeralBackupPrincipal,
    [switch]$KeepRestoreDatabase
)

$ErrorActionPreference = 'Stop'
$rootPath = [IO.Path]::GetFullPath($Root)
if ($Maven) {
    $Maven = (Get-Item -LiteralPath $Maven -ErrorAction Stop).FullName
} else {
    $mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (-not $mavenCommand) { $mavenCommand = Get-Command mvn -ErrorAction Stop }
    $Maven = $mavenCommand.Source
}
if ($SourceDatabase -notmatch '^[A-Za-z0-9_]+$') { throw 'SourceDatabase contains unsafe characters' }
if ($RestoreDatabase -notmatch '^aimall_restore_stage20_[A-Za-z0-9_]+$') { throw 'RestoreDatabase must use aimall_restore_stage20_*' }
$mysql = (Get-Command mysql -ErrorAction Stop).Source
$mysqldump = (Get-Command mysqldump -ErrorAction Stop).Source
$evidence = [IO.Path]::GetFullPath((Join-Path $rootPath $EvidenceDirectory))
[IO.Directory]::CreateDirectory($evidence) | Out-Null
$runId = 'S20-' + (Get-Date -Format 'yyyyMMddHHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$plainDump = Join-Path ([IO.Path]::GetTempPath()) "$runId.sql"
$plainRestore = Join-Path ([IO.Path]::GetTempPath()) "$runId.restore.sql"
$cipherTemp = Join-Path ([IO.Path]::GetTempPath()) "$runId.cipher"
$encryptedBackup = Join-Path $evidence "$runId.aibak"
$ephemeralPrincipal = $false
$principalHost = '%'
$completedSuccessfully = $false

function Import-EnvFile([string]$path) {
    if (-not (Test-Path $path)) { return }
    foreach ($line in Get-Content $path -Encoding UTF8) {
        $value = $line.Trim()
        if (-not $value -or $value.StartsWith('#') -or -not $value.Contains('=')) { continue }
        $parts = $value.Split('=', 2)
        if (-not [Environment]::GetEnvironmentVariable($parts[0].Trim(), 'Process')) {
            [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim().Trim('"').Trim("'"), 'Process')
        }
    }
}

function Invoke-DbCommand([string]$username, [string]$password, [string[]]$arguments) {
    $previous = $env:MYSQL_PWD
    $env:MYSQL_PWD = $password
    try {
        $result = & $mysql --host=$HostName --port=$Port --user=$username @arguments
        if ($LASTEXITCODE -ne 0) { throw "mysql command failed for user $username" }
        return $result
    } finally {
        $env:MYSQL_PWD = $previous
    }
}

function Query-Scalar([string]$database, [string]$sql) {
    $result = Invoke-DbCommand $AdminUsername $AdminPassword @(
        '--batch', '--skip-column-names', "--database=$database", "--execute=$sql"
    )
    return [string]($result | Select-Object -First 1)
}

function Invoke-RestoreImport([string]$database, [string]$sqlFile) {
    if ($AdminUsername -notmatch '^[A-Za-z0-9_]+$') { throw 'AdminUsername contains unsafe characters' }
    $previous = $env:MYSQL_PWD
    $env:MYSQL_PWD = $AdminPassword
    try {
        $start = New-Object Diagnostics.ProcessStartInfo
        $start.FileName = $mysql
        $start.Arguments = "--host=$HostName --port=$Port --user=$AdminUsername $database"
        $start.UseShellExecute = $false
        $start.RedirectStandardInput = $true
        $process = New-Object Diagnostics.Process
        $process.StartInfo = $start
        if (-not $process.Start()) { throw 'could not start mysql restore process' }
        $source = [IO.File]::OpenRead($sqlFile)
        try { $source.CopyTo($process.StandardInput.BaseStream) }
        finally { $source.Dispose(); $process.StandardInput.Close() }
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw 'restore import failed' }
    } finally { $env:MYSQL_PWD = $previous }
}

function New-KeyMaterial([string]$password, [byte[]]$salt) {
    $derive = New-Object Security.Cryptography.Rfc2898DeriveBytes(
        $password, $salt, 200000, [Security.Cryptography.HashAlgorithmName]::SHA256
    )
    try { return [byte[]]$derive.GetBytes(64) } finally { $derive.Dispose() }
}

function Add-Hmac([string]$path, [byte[]]$key) {
    $hmac = New-Object Security.Cryptography.HMACSHA256(,$key)
    $stream = [IO.File]::OpenRead($path)
    try {
        $buffer = New-Object byte[] (1024 * 1024)
        while (($read = $stream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            [void]$hmac.TransformBlock($buffer, 0, $read, $buffer, 0)
        }
        [void]$hmac.TransformFinalBlock((New-Object byte[] 0), 0, 0)
    } finally { $stream.Dispose() }
    $append = [IO.File]::Open($path, [IO.FileMode]::Append, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $append.Write($hmac.Hash, 0, $hmac.Hash.Length) } finally { $append.Dispose(); $hmac.Dispose() }
}

function Protect-Backup([string]$source, [string]$target, [string]$password) {
    $salt = New-Object byte[] 16
    $iv = New-Object byte[] 16
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($salt); $rng.GetBytes($iv) } finally { $rng.Dispose() }
    $material = New-KeyMaterial $password $salt
    [byte[]]$encKey = $material[0..31]
    [byte[]]$macKey = $material[32..63]
    $aes = [Security.Cryptography.Aes]::Create()
    $aes.KeySize = 256; $aes.Mode = [Security.Cryptography.CipherMode]::CBC
    $aes.Padding = [Security.Cryptography.PaddingMode]::PKCS7; $aes.Key = $encKey; $aes.IV = $iv
    $output = [IO.File]::Open($target, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $header = [Text.Encoding]::ASCII.GetBytes('AIMBKP01')
    $output.Write($header, 0, $header.Length); $output.Write($salt, 0, 16); $output.Write($iv, 0, 16)
    $crypto = New-Object Security.Cryptography.CryptoStream($output, $aes.CreateEncryptor(), [Security.Cryptography.CryptoStreamMode]::Write)
    $input = [IO.File]::OpenRead($source)
    try { $input.CopyTo($crypto); $crypto.FlushFinalBlock() }
    finally { $input.Dispose(); $crypto.Dispose(); $aes.Dispose() }
    Add-Hmac $target $macKey
}

function Test-FixedTimeEqual([byte[]]$left, [byte[]]$right) {
    if ($left.Length -ne $right.Length) { return $false }
    $difference = 0
    for ($index = 0; $index -lt $left.Length; $index++) { $difference = $difference -bor ($left[$index] -bxor $right[$index]) }
    return $difference -eq 0
}

function Unprotect-Backup([string]$source, [string]$target, [string]$password) {
    $input = [IO.File]::OpenRead($source)
    try {
        if ($input.Length -le 72) { throw 'encrypted backup is truncated' }
        $header = New-Object byte[] 8; [void]$input.Read($header, 0, 8)
        if ([Text.Encoding]::ASCII.GetString($header) -ne 'AIMBKP01') { throw 'invalid encrypted backup header' }
        $salt = New-Object byte[] 16; [void]$input.Read($salt, 0, 16)
        $iv = New-Object byte[] 16; [void]$input.Read($iv, 0, 16)
        $tagOffset = $input.Length - 32
        $input.Position = $tagOffset
        $storedTag = New-Object byte[] 32; [void]$input.Read($storedTag, 0, 32)
    } finally { $input.Dispose() }
    $material = New-KeyMaterial $password $salt
    [byte[]]$encKey = $material[0..31]; [byte[]]$macKey = $material[32..63]
    $hmac = New-Object Security.Cryptography.HMACSHA256(,$macKey)
    $verify = [IO.File]::OpenRead($source)
    try {
        $remaining = $tagOffset; $buffer = New-Object byte[] (1024 * 1024)
        while ($remaining -gt 0) {
            $read = $verify.Read($buffer, 0, [Math]::Min($buffer.Length, $remaining))
            if ($read -le 0) { throw 'encrypted backup ended before HMAC' }
            [void]$hmac.TransformBlock($buffer, 0, $read, $buffer, 0); $remaining -= $read
        }
        [void]$hmac.TransformFinalBlock((New-Object byte[] 0), 0, 0)
        if (-not (Test-FixedTimeEqual $hmac.Hash $storedTag)) { throw 'encrypted backup HMAC verification failed' }
    } finally { $verify.Dispose(); $hmac.Dispose() }
    $sourceStream = [IO.File]::OpenRead($source); $sourceStream.Position = 40
    $cipherStream = [IO.File]::Open($cipherTemp, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $remaining = $tagOffset - 40; $buffer = New-Object byte[] (1024 * 1024)
        while ($remaining -gt 0) {
            $read = $sourceStream.Read($buffer, 0, [Math]::Min($buffer.Length, $remaining))
            if ($read -le 0) { throw 'encrypted payload is truncated' }
            $cipherStream.Write($buffer, 0, $read); $remaining -= $read
        }
    } finally { $sourceStream.Dispose(); $cipherStream.Dispose() }
    $aes = [Security.Cryptography.Aes]::Create()
    $aes.KeySize = 256; $aes.Mode = [Security.Cryptography.CipherMode]::CBC
    $aes.Padding = [Security.Cryptography.PaddingMode]::PKCS7; $aes.Key = $encKey; $aes.IV = $iv
    $cipherInput = [IO.File]::OpenRead($cipherTemp)
    $crypto = New-Object Security.Cryptography.CryptoStream($cipherInput, $aes.CreateDecryptor(), [Security.Cryptography.CryptoStreamMode]::Read)
    $output = [IO.File]::Open($target, [IO.FileMode]::Create, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $crypto.CopyTo($output) } finally { $output.Dispose(); $crypto.Dispose(); $cipherInput.Dispose(); $aes.Dispose() }
}

function Table-Snapshot([string]$database, [string]$table) {
    $row = Query-Scalar $database "SELECT CONCAT(COUNT(*),':',COALESCE(MAX(id),0)) FROM ``$table``"
    $parts = $row.Split(':', 2)
    return [ordered]@{ rowCount = [long]$parts[0]; maxId = [long]$parts[1] }
}

Import-EnvFile (Join-Path $rootPath '.env')
if ([string]::IsNullOrWhiteSpace($AdminPassword)) { $AdminPassword = $env:MYSQL_ROOT_PASSWORD }
if ([string]::IsNullOrWhiteSpace($BackupUsername)) { $BackupUsername = $env:AIMALL_BACKUP_DB_USERNAME }
if ([string]::IsNullOrWhiteSpace($BackupPassword)) { $BackupPassword = $env:AIMALL_BACKUP_DB_PASSWORD }
if ([string]::IsNullOrWhiteSpace($EncryptionKey)) { $EncryptionKey = $env:AIMALL_BACKUP_ENCRYPTION_KEY }
if ([string]::IsNullOrWhiteSpace($AdminPassword)) { throw 'AdminPassword or MYSQL_ROOT_PASSWORD is required' }
if ([string]::IsNullOrWhiteSpace($EncryptionKey) -or $EncryptionKey.Length -lt 32) {
    throw 'AIMALL_BACKUP_ENCRYPTION_KEY must contain at least 32 characters'
}

try {
    if ($ProvisionEphemeralBackupPrincipal) {
        $BackupUsername = 'stage20_bkp_' + $PID
        $BackupPassword = [Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N')
        $sql = "DROP USER IF EXISTS '$BackupUsername'@'$principalHost'; CREATE USER '$BackupUsername'@'$principalHost' IDENTIFIED BY '$BackupPassword'; GRANT SELECT, SHOW VIEW, TRIGGER, EVENT ON ``$SourceDatabase``.* TO '$BackupUsername'@'$principalHost'; FLUSH PRIVILEGES;"
        [void](Invoke-DbCommand $AdminUsername $AdminPassword @("--execute=$sql"))
        $ephemeralPrincipal = $true
    }
    if ([string]::IsNullOrWhiteSpace($BackupUsername) -or [string]::IsNullOrWhiteSpace($BackupPassword)) {
        throw 'A dedicated backup username and password are required'
    }

    $domainTables = @(
        'oms_order','oms_payment_record','oms_refund_record','inventory_ledger',
        'outbox_event','admin_operation_audit','knowledge_doc_audit_log','knowledge_doc_version'
    )
    $sourceSnapshot = [ordered]@{}
    foreach ($table in $domainTables) { $sourceSnapshot[$table] = Table-Snapshot $SourceDatabase $table }
    $recoveryPointUtc = Query-Scalar $SourceDatabase 'SELECT DATE_FORMAT(UTC_TIMESTAMP(6),''%Y-%m-%dT%H:%i:%s.%fZ'')'
    $backupStarted = [DateTimeOffset]::UtcNow
    $previous = $env:MYSQL_PWD; $env:MYSQL_PWD = $BackupPassword
    try {
        & $mysqldump --host=$HostName --port=$Port --user=$BackupUsername --single-transaction --quick `
            --routines --events --triggers --set-gtid-purged=OFF --no-tablespaces --default-character-set=utf8mb4 `
            "--result-file=$plainDump" $SourceDatabase
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path $plainDump)) { throw 'mysqldump failed' }
    } finally { $env:MYSQL_PWD = $previous }
    $plainHash = (Get-FileHash $plainDump -Algorithm SHA256).Hash
    Protect-Backup $plainDump $encryptedBackup $EncryptionKey
    Remove-Item $plainDump -Force

    $restoreStarted = [DateTimeOffset]::UtcNow
    Unprotect-Backup $encryptedBackup $plainRestore $EncryptionKey
    if ((Get-FileHash $plainRestore -Algorithm SHA256).Hash -ne $plainHash) { throw 'decrypted dump hash mismatch' }
    [void](Invoke-DbCommand $AdminUsername $AdminPassword @("--execute=DROP DATABASE IF EXISTS ``$RestoreDatabase``; CREATE DATABASE ``$RestoreDatabase`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"))
    Invoke-RestoreImport $RestoreDatabase $plainRestore

    $restoreBeforeMigration = [ordered]@{}
    foreach ($table in $domainTables) {
        $restoreBeforeMigration[$table] = Table-Snapshot $RestoreDatabase $table
        if ($restoreBeforeMigration[$table].rowCount -ne $sourceSnapshot[$table].rowCount -or
                $restoreBeforeMigration[$table].maxId -ne $sourceSnapshot[$table].maxId) {
            throw "domain snapshot mismatch: $table"
        }
    }
    $sourceFlyway = Query-Scalar $SourceDatabase "SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"
    if (-not (Test-Path $Maven)) { throw "Maven not found: $Maven" }
    $previousDbUser = $env:AIMALL_DB_USERNAME
    $previousDbPassword = $env:AIMALL_DB_PASSWORD
    $previousDrillDatabase = $env:AIMALL_FLYWAY_DRILL_DATABASE
    $env:AIMALL_DB_USERNAME = $AdminUsername
    $env:AIMALL_DB_PASSWORD = $AdminPassword
    $env:AIMALL_FLYWAY_DRILL_DATABASE = $RestoreDatabase
    try {
        Push-Location (Join-Path $rootPath 'aimall-server')
        & $Maven -B '-Daimall.flyway.recovery-drill=true' '-Dtest=FlywayDisasterRecoveryIntegrationTest' test
        if ($LASTEXITCODE -ne 0) { throw 'restored snapshot Flyway migration failed' }
    } finally {
        Pop-Location -ErrorAction SilentlyContinue
        $env:AIMALL_DB_USERNAME = $previousDbUser
        $env:AIMALL_DB_PASSWORD = $previousDbPassword
        $env:AIMALL_FLYWAY_DRILL_DATABASE = $previousDrillDatabase
    }

    $restoreSnapshot = [ordered]@{}
    foreach ($table in $domainTables) {
        $restoreSnapshot[$table] = Table-Snapshot $RestoreDatabase $table
        if ($restoreSnapshot[$table].rowCount -lt $sourceSnapshot[$table].rowCount -or
                $restoreSnapshot[$table].maxId -lt $sourceSnapshot[$table].maxId) {
            throw "post-migration domain snapshot regressed: $table"
        }
    }
    $invariantSql = [ordered]@{
        orphanOrderItems = 'SELECT COUNT(*) FROM oms_order_item item LEFT JOIN oms_order o ON o.id=item.order_id WHERE o.id IS NULL'
        orphanPayments = 'SELECT COUNT(*) FROM oms_payment_record p LEFT JOIN oms_order o ON o.id=p.order_id WHERE o.id IS NULL'
        orphanRefundOrders = 'SELECT COUNT(*) FROM oms_refund_record r LEFT JOIN oms_order o ON o.id=r.order_id WHERE o.id IS NULL'
        invalidSkuStock = 'SELECT COUNT(*) FROM pms_sku_stock WHERE stock < 0 OR lock_stock < 0 OR lock_stock > stock'
        malformedOutbox = 'SELECT COUNT(*) FROM outbox_event WHERE payload_json IS NULL OR JSON_VALID(payload_json)=0'
        orphanCurrentKnowledgeVersion = 'SELECT COUNT(*) FROM knowledge_doc d LEFT JOIN knowledge_doc_version v ON v.id=d.current_version_id WHERE d.current_version_id IS NOT NULL AND v.id IS NULL'
    }
    $invariants = [ordered]@{}
    foreach ($name in $invariantSql.Keys) {
        $sourceValue = [long](Query-Scalar $SourceDatabase $invariantSql[$name])
        $restoreValue = [long](Query-Scalar $RestoreDatabase $invariantSql[$name])
        if ($restoreValue -gt $sourceValue) { throw "migration introduced invariant violations: $name" }
        $invariants[$name] = [ordered]@{ source = $sourceValue; restored = $restoreValue; noRegression = $true }
    }
    $latestFlyway = Query-Scalar $RestoreDatabase "SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1"
    $completed = [DateTimeOffset]::UtcNow
    $rtoSeconds = [Math]::Round(($completed - $restoreStarted).TotalSeconds, 3)
    $result = [ordered]@{
        schemaVersion = 'AIMALL_STAGE20_RECOVERY_EVIDENCE_V1'
        runId = $runId
        generatedAt = [DateTimeOffset]::Now.ToString('o')
        sourceDatabase = $SourceDatabase
        restoreDatabase = $RestoreDatabase
        recoveryPointUtc = $recoveryPointUtc
        backupDurationSeconds = [Math]::Round(($restoreStarted - $backupStarted).TotalSeconds, 3)
        rtoSeconds = $rtoSeconds
        rtoTargetSeconds = 3600
        rtoPassed = $rtoSeconds -le 3600
        encryptedBackup = (($EvidenceDirectory.TrimEnd('/','\') + '/' + $runId + '.aibak').Replace('\','/'))
        encryptedBackupBytes = (Get-Item $encryptedBackup).Length
        encryptedBackupSha256 = (Get-FileHash $encryptedBackup -Algorithm SHA256).Hash
        encryption = 'PBKDF2-SHA256-200000/AES-256-CBC/HMAC-SHA256'
        plaintextPersisted = $false
        backupPrincipal = [ordered]@{ username = $BackupUsername; ephemeral = $ephemeralPrincipal; rootUsedForDump = $false }
        sourceFlywayVersion = $sourceFlyway
        latestFlywayVersion = $latestFlyway
        restoredBeforeMigration = $restoreBeforeMigration
        domainSnapshots = $restoreSnapshot
        invariants = $invariants
        passed = $rtoSeconds -le 3600
    }
    [IO.File]::WriteAllText((Join-Path $evidence 'result.json'), ($result | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
    $completedSuccessfully = $true
    $result | ConvertTo-Json -Depth 12
} finally {
    foreach ($path in @($plainDump, $plainRestore, $cipherTemp)) {
        if (Test-Path $path) { Remove-Item -LiteralPath $path -Force }
    }
    if (-not $KeepRestoreDatabase) {
        try { [void](Invoke-DbCommand $AdminUsername $AdminPassword @("--execute=DROP DATABASE IF EXISTS ``$RestoreDatabase``;")) } catch { }
    }
    if ($ephemeralPrincipal) {
        try { [void](Invoke-DbCommand $AdminUsername $AdminPassword @("--execute=DROP USER IF EXISTS '$BackupUsername'@'$principalHost';")) } catch { }
    }
    if (-not $completedSuccessfully -and (Test-Path $encryptedBackup)) {
        Remove-Item -LiteralPath $encryptedBackup -Force
    }
}
