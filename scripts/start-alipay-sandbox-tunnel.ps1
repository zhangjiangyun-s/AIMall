param(
    [string]$TargetUrl = "http://127.0.0.1:5173",
    [string]$Workspace = (Resolve-Path (Join-Path $PSScriptRoot "..")),
    [switch]$RestartServer
)

$ErrorActionPreference = "Stop"
$envFile = Join-Path $Workspace ".env"
$stdoutFile = Join-Path $Workspace "cloudflared-alipay-tunnel.stdout.log"
$stderrFile = Join-Path $Workspace "cloudflared-alipay-tunnel.stderr.log"
$cloudflared = (Get-Command cloudflared -ErrorAction SilentlyContinue).Source
if (-not $cloudflared) {
    throw "cloudflared.exe was not found. Install Cloudflare Tunnel first."
}
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "The .env file was not found: $envFile"
}

if (Test-Path -LiteralPath $stdoutFile) { Remove-Item -LiteralPath $stdoutFile -Force }
if (Test-Path -LiteralPath $stderrFile) { Remove-Item -LiteralPath $stderrFile -Force }
$process = Start-Process -FilePath $cloudflared `
    -ArgumentList @("tunnel", "--url", $TargetUrl) `
    -WorkingDirectory $Workspace `
    -RedirectStandardOutput $stdoutFile `
    -RedirectStandardError $stderrFile `
    -WindowStyle Hidden `
    -PassThru

try {
    $tunnelUrl = $null
    for ($attempt = 0; $attempt -lt 30 -and -not $tunnelUrl; $attempt++) {
        Start-Sleep -Seconds 1
        if ($process.HasExited) {
            throw "cloudflared exited. Check $stdoutFile and $stderrFile"
        }
        if ((Test-Path -LiteralPath $stdoutFile) -or (Test-Path -LiteralPath $stderrFile)) {
            $content = ""
            if (Test-Path -LiteralPath $stdoutFile) { $content += Get-Content -LiteralPath $stdoutFile -Raw -ErrorAction SilentlyContinue }
            if (Test-Path -LiteralPath $stderrFile) { $content += Get-Content -LiteralPath $stderrFile -Raw -ErrorAction SilentlyContinue }
            $match = [regex]::Match($content, "https://[a-z0-9-]+\.trycloudflare\.com")
            if ($match.Success) { $tunnelUrl = $match.Value }
        }
    }
    if (-not $tunnelUrl) {
        throw "No Cloudflare public URL was found within 30 seconds. Check $stdoutFile and $stderrFile"
    }

    $lines = Get-Content -LiteralPath $envFile
    $updated = $false
    $lines = $lines | ForEach-Object {
        if ($_ -match '^ALIPAY_NOTIFY_BASE_URL=') {
            $updated = $true
            "ALIPAY_NOTIFY_BASE_URL=$tunnelUrl"
        } else {
            $_
        }
    }
    if (-not $updated) { $lines += "ALIPAY_NOTIFY_BASE_URL=$tunnelUrl" }
    Set-Content -LiteralPath $envFile -Value $lines -Encoding UTF8

    Write-Host "Cloudflare callback base URL written to .env: $tunnelUrl"
    Write-Host "Alipay notify URL: $tunnelUrl/api/pay/alipay/notify"
    Write-Host "Quick Tunnel URLs change after restart. Run this script again and restart the server."

    if ($RestartServer) {
        Write-Host "Docker application restart is disabled for this project. Restart the local Java server manually."
    } else {
        Write-Host "Restart the local Java server to load the new ALIPAY_NOTIFY_BASE_URL."
    }
} catch {
    if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force }
    throw
}
