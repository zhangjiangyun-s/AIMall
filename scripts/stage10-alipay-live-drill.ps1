param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = "Stop"
$rootPath = [System.IO.Path]::GetFullPath($Root)
$envFile = Join-Path $rootPath ".env"
$lines = Get-Content $envFile
function Env-Value([string]$name) {
    $line = $lines | Where-Object { $_ -match "^$([regex]::Escape($name))=" } | Select-Object -First 1
    if (-not $line) { return $null }
    return ($line -split '=', 2)[1].Trim().Trim('"').Trim("'")
}
$mysql = (Get-Command mysql -ErrorAction Stop).Source
function Query-Db([string]$sql) {
    $env:MYSQL_PWD = Env-Value "AIMALL_DB_PASSWORD"
    try {
        return & $mysql -h 127.0.0.1 -P 3306 `
            -u (Env-Value "AIMALL_DB_USERNAME") -N -B aimall -e $sql
    } finally { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
}

$paid = @(Query-Db "SELECT order_sn,amount FROM oms_payment_record WHERE pay_channel='ALIPAY_SANDBOX' AND pay_status='PAID' AND transaction_no IS NOT NULL ORDER BY id DESC LIMIT 1;")
$refund = @(Query-Db "SELECT request_id,order_sn,amount FROM oms_refund_record WHERE refund_channel='ALIPAY_SANDBOX' AND refund_status='SUCCEEDED' ORDER BY id DESC LIMIT 1;")
$closed = @(Query-Db "SELECT order_sn FROM oms_payment_record WHERE pay_channel='ALIPAY_SANDBOX' AND payment_state='CLOSED' ORDER BY id DESC LIMIT 1;")
if ($paid.Count -ne 1 -or $refund.Count -ne 1 -or $closed.Count -ne 1) {
    throw "Existing paid, refunded and closed Alipay sandbox evidence is required."
}
$paidFields = $paid[0] -split "`t"
$refundFields = $refund[0] -split "`t"

$keys = @(
    "ALIPAY_APP_ID", "ALIPAY_SELLER_ID", "ALIPAY_PRIVATE_KEY_FILE", "ALIPAY_PUBLIC_KEY_FILE",
    "ALIPAY_GATEWAY_URL", "ALIPAY_SIGN_TYPE", "ALIPAY_CHARSET", "ALIPAY_FORMAT"
)
foreach ($key in $keys) {
    $value = Env-Value $key
    if ([string]::IsNullOrWhiteSpace($value)) { throw "$key is missing from .env." }
    if ($key -in @("ALIPAY_PRIVATE_KEY_FILE", "ALIPAY_PUBLIC_KEY_FILE") -and -not [IO.Path]::IsPathRooted($value)) {
        $value = [IO.Path]::GetFullPath((Join-Path $rootPath $value))
    }
    [Environment]::SetEnvironmentVariable($key, $value, "Process")
}
$env:ALIPAY_E2E_PAID_ORDER_SN = $paidFields[0]
$env:ALIPAY_E2E_PAID_AMOUNT = $paidFields[1]
$env:ALIPAY_E2E_REFUND_REQUEST_ID = $refundFields[0]
$env:ALIPAY_E2E_REFUND_ORDER_SN = $refundFields[1]
$env:ALIPAY_E2E_REFUND_AMOUNT = $refundFields[2]
$env:ALIPAY_E2E_CLOSED_ORDER_SN = $closed[0]

$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $mavenCommand) { $mavenCommand = Get-Command mvn -ErrorAction Stop }
$maven = $mavenCommand.Source
try {
    Push-Location (Join-Path $rootPath "aimall-server")
    & $maven -B '-Daimall.alipay.live=true' '-Dtest=AlipaySandboxLiveIntegrationTest' test
    if ($LASTEXITCODE -ne 0) { throw "Alipay live integration test failed." }
} finally {
    Pop-Location -ErrorAction SilentlyContinue
    foreach ($key in $keys + @(
        "ALIPAY_E2E_PAID_ORDER_SN", "ALIPAY_E2E_PAID_AMOUNT", "ALIPAY_E2E_REFUND_REQUEST_ID",
        "ALIPAY_E2E_REFUND_ORDER_SN", "ALIPAY_E2E_REFUND_AMOUNT", "ALIPAY_E2E_CLOSED_ORDER_SN"
    )) { Remove-Item "Env:$key" -ErrorAction SilentlyContinue }
}

$batch = @(Query-Db "SELECT id,status,checked_count,difference_count FROM payment_reconciliation_batch WHERE provider='ALIPAY_SANDBOX' ORDER BY id DESC LIMIT 1;")
if ($batch.Count -ne 1) { throw "No Alipay reconciliation batch exists." }
$batchFields = $batch[0] -split "`t"
if ($batchFields[1] -ne "COMPLETED" -or [int]$batchFields[3] -ne 0) {
    throw "Latest Alipay reconciliation batch is not clean."
}
$result = [ordered]@{
    passed = $true
    liveGateway = $true
    paymentQuery = "PASSED"
    refundQuery = "PASSED"
    closeIdempotency = "PASSED"
    verifiedCallbackCount = [int](Query-Db "SELECT COUNT(*) FROM payment_callback_event WHERE provider='ALIPAY_SANDBOX' AND signature_valid=1 AND processing_state='PROCESSED';")
    successfulRefundCount = [int](Query-Db "SELECT COUNT(*) FROM oms_refund_record WHERE refund_channel='ALIPAY_SANDBOX' AND refund_status='SUCCEEDED' AND provider_status='SUCCEEDED';")
    reconciliation = [ordered]@{
        batchId = [long]$batchFields[0]
        status = $batchFields[1]
        checkedCount = [int]$batchFields[2]
        differenceCount = [int]$batchFields[3]
    }
}
$output = Join-Path $rootPath ".acceptance/stage10/live-alipay-e2e.json"
$result | ConvertTo-Json | Set-Content $output -Encoding UTF8
Write-Output $output
