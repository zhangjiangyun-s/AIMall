param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [int]$Port = 4175
)

$ErrorActionPreference = "Stop"
$webRoot = Join-Path $Root "aimall-web"
$node = (Get-Command node -ErrorAction Stop).Source
$vite = Join-Path $webRoot "node_modules/vite/bin/vite.js"
$playwright = Join-Path $webRoot "node_modules/@playwright/test/cli.js"
if (-not (Test-Path $vite) -or -not (Test-Path $playwright)) {
    throw "Run npm ci in aimall-web before the Stage 10 E2E gate."
}

$server = Start-Process -FilePath $node -ArgumentList @($vite, "--host", "127.0.0.1", "--port", $Port) `
    -WorkingDirectory $webRoot -WindowStyle Hidden -PassThru
try {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 250
        try {
            $response = Invoke-WebRequest -Uri "http://127.0.0.1:$Port" -UseBasicParsing -TimeoutSec 2
            $ready = $response.StatusCode -eq 200
        } catch { $ready = $false }
    } until ($ready -or [DateTime]::UtcNow -ge $deadline)
    if (-not $ready) { throw "Vite did not become ready on port $Port." }

    $env:STAGE10_EXTERNAL_WEB_SERVER = "1"
    & $node $playwright test --config (Join-Path $webRoot "playwright.config.ts") --reporter=line
    if ($LASTEXITCODE -ne 0) { throw "Stage 10 Playwright E2E failed with exit code $LASTEXITCODE." }
} finally {
    Remove-Item Env:STAGE10_EXTERNAL_WEB_SERVER -ErrorAction SilentlyContinue
    if (-not $server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
}
