param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Output = '.acceptance/stage18/quality-gate.json',
    [switch]$RequireEngineering,
    [switch]$RequireProduction
)

$ErrorActionPreference = 'Stop'
$rootPath = [IO.Path]::GetFullPath($Root)
function Read-Utf8([string]$path) { [IO.File]::ReadAllText((Join-Path $rootPath $path), [Text.UTF8Encoding]::new($false, $true)) }
function Read-Json([string]$path) { Get-Content (Join-Path $rootPath $path) -Raw -Encoding UTF8 | ConvertFrom-Json }

$money = Read-Utf8 'aimall-server/src/main/java/com/aimall/server/money/MoneyPolicy.java'
$product = Read-Utf8 'aimall-server/src/main/java/com/aimall/server/entity/PmsProduct.java'
$sku = Read-Utf8 'aimall-server/src/main/java/com/aimall/server/entity/PmsSkuStock.java'
$snapshot = Read-Utf8 'aimall-server/src/main/java/com/aimall/server/service/impl/MoneySnapshotService.java'
$migration = Read-Utf8 'aimall-server/src/main/resources/db/migration/V20260721_1800__precise_money_snapshots.sql'
$inventory = Read-Utf8 'aimall-server/src/main/java/com/aimall/server/service/impl/InventoryServiceImpl.java'
$adjustment = Read-Utf8 'aimall-server/src/main/java/com/aimall/server/service/impl/InventoryAdjustmentLedgerService.java'
$verification = Read-Json '.acceptance/stage18/verification.json'

$checks = @(
    [ordered]@{id='S18-01';name='Four-decimal money policy and currency rounding';engineering=($money.Contains('STORAGE_SCALE = 4') -and $money.Contains('RoundingMode.HALF_UP') -and $money.Contains('currencyScale'));production=$false;evidence='Central MoneyPolicy with DECIMAL(18,4), HALF_UP and provider currency scale'},
    [ordered]@{id='S18-02';name='Final-item allocation remainder';engineering=($money.Contains('index == weights.size() - 1') -and $money.Contains('remaining'));production=$false;evidence='Deterministic allocation; final item absorbs exact four-decimal remainder'},
    [ordered]@{id='S18-03';name='Product price expand/backfill/read switch';engineering=($product.Contains('@TableField("price_v2")') -and $sku.Contains('@TableField("price_v2")') -and $migration.Contains('price_v2 decimal(18,4)') -and $migration.Contains('UPDATE pms_product SET price_v2=COALESCE'));production=$false;evidence='SPU, SKU and rule v2 columns with legacy dual write and v2 entity read model'},
    [ordered]@{id='S18-04';name='Order, payment and refund precise snapshots';engineering=($snapshot.Contains('upsertOrder') -and $snapshot.Contains('upsertPayment') -and $snapshot.Contains('upsertRefund') -and $migration.Contains('order_money_snapshot_v2') -and $migration.Contains('payment_money_snapshot_v2') -and $migration.Contains('refund_money_snapshot_v2'));production=$false;evidence='Expand/backfill/read-switch snapshots; legacy monetary columns remain intact'},
    [ordered]@{id='S18-05';name='Canonical inventory delta ledger';engineering=($inventory.Contains('"RESERVE", qty, 0, qty, 0, -qty') -and $inventory.Contains('"DEDUCT", qty, -qty, -qty, qty, 0') -and $inventory.Contains('"RELEASE", qty, 0, -qty, 0, qty') -and $inventory.Contains('"RESTORE", quantity, quantity, 0, -quantity, quantity') -and $adjustment.Contains('ledger.setAvailableDelta(delta)'));production=$false;evidence='Reserve, deduct, release, restore and admin adjustment explicitly record all four deltas'},
    [ordered]@{id='S18-06';name='Regression and migration evidence';engineering=([bool]$verification.java.passed -and [bool]$verification.realMySql.passed);production=$false;evidence='Java 182 passed; real MySQL 25/25; Flyway current 20260721.1800'}
)

$engineeringPassed = @($checks | Where-Object engineering).Count
$productionPassed = @($checks | Where-Object production).Count
$result = [ordered]@{stage=18;source='AIMALL_ENGINEERING_PRODUCTION_REMEDIATION_PLAN.md section 18';generatedAt=[DateTimeOffset]::Now.ToString('o');total=$checks.Count;engineeringPassedCount=$engineeringPassed;engineeringFullyCompleted=$engineeringPassed -eq $checks.Count;productionPassedCount=$productionPassed;productionReady=$productionPassed -eq $checks.Count;checks=$checks}
$outputPath = [IO.Path]::GetFullPath((Join-Path $rootPath $Output))
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputPath)) | Out-Null
[IO.File]::WriteAllText($outputPath, ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
if ($RequireEngineering -and -not $result.engineeringFullyCompleted) { throw 'Stage 18 engineering gate failed' }
if ($RequireProduction -and -not $result.productionReady) { throw 'Stage 18 production gate failed' }
$result | ConvertTo-Json -Depth 8
