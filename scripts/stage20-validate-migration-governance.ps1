param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Output = '.acceptance/stage20/migration-governance.json'
)

$ErrorActionPreference = 'Stop'
$rootPath = [IO.Path]::GetFullPath($Root)
$metadataPath = Join-Path $rootPath 'docs/operations/migration-governance.json'
$metadata = Get-Content $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
$migrationRoot = Join-Path $rootPath $metadata.migrationDirectory
$actual = @(Get-ChildItem $migrationRoot -Filter 'V*.sql' -File | Sort-Object Name | ForEach-Object Name)
$declared = @($metadata.migrations | ForEach-Object file | Sort-Object)
$errors = [Collections.Generic.List[string]]::new()

if ($metadata.schemaVersion -ne 'AIMALL_MIGRATION_GOVERNANCE_V1') { $errors.Add('invalid schemaVersion') }
foreach ($file in $actual) {
    if ($file -notin $declared) { $errors.Add("missing metadata: $file") }
}
foreach ($file in $declared) {
    if ($file -notin $actual) { $errors.Add("metadata references missing migration: $file") }
}

$requiredProfileFields = @(
    'estimatedLockTime','tableSizeQuery','onlineDdl','batchSize',
    'progressQuery','failureRecovery','backupPoint','compatibilityWindow'
)
foreach ($migration in $metadata.migrations) {
    if ([string]::IsNullOrWhiteSpace($migration.phase)) { $errors.Add("missing phase: $($migration.file)") }
    if ([string]::IsNullOrWhiteSpace($migration.owner)) { $errors.Add("missing owner: $($migration.file)") }
    $profile = $metadata.profiles.($migration.profile)
    if ($null -eq $profile) {
        $errors.Add("missing profile $($migration.profile): $($migration.file)")
        continue
    }
    foreach ($field in $requiredProfileFields) {
        if ([string]::IsNullOrWhiteSpace([string]$profile.$field)) {
            $errors.Add("profile $($migration.profile) missing $field")
        }
    }
    $sql = [IO.File]::ReadAllText((Join-Path $migrationRoot $migration.file))
    $destructive = $migration.file -ne 'V20260716_0000__baseline_schema.sql' -and
        $sql -match '(?i)\b(DROP\s+(TABLE|COLUMN|INDEX)|MODIFY\s+COLUMN|TRUNCATE\s+TABLE)\b'
    if ($destructive -and -not [bool]$migration.approvalRequired) {
        $errors.Add("destructive migration is not approval-gated: $($migration.file)")
    }
}

$result = [ordered]@{
    stage = 20
    generatedAt = [DateTimeOffset]::Now.ToString('o')
    migrationCount = $actual.Count
    metadataCount = $declared.Count
    profileCount = @($metadata.profiles.PSObject.Properties).Count
    destructiveApprovalGates = @($metadata.migrations | Where-Object approvalRequired).Count
    errors = $errors
    passed = $errors.Count -eq 0
}
$outputPath = [IO.Path]::GetFullPath((Join-Path $rootPath $Output))
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputPath)) | Out-Null
[IO.File]::WriteAllText($outputPath, ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
if (-not $result.passed) { throw "Migration governance failed: $($errors -join '; ')" }
$result | ConvertTo-Json -Depth 8
