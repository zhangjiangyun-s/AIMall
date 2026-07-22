param(
    [string]$OutputPath = ".acceptance/stage7/runtime-smoke.json"
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$acceptanceDir = Join-Path $root ".acceptance/stage7"
New-Item -ItemType Directory -Force -Path $acceptanceDir | Out-Null

function Import-DotEnv([string]$path) {
    if (-not (Test-Path -LiteralPath $path)) { return }
    foreach ($line in Get-Content -LiteralPath $path -Encoding UTF8) {
        $text = $line.Trim()
        if (-not $text -or $text.StartsWith("#") -or -not $text.Contains("=")) { continue }
        $parts = $text.Split("=", 2)
        [Environment]::SetEnvironmentVariable($parts[0].Trim(), $parts[1].Trim().Trim('"').Trim("'"), "Process")
    }
}

function Wait-Http([string]$url, [int]$seconds = 90) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -eq 200) { return $response }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "Timed out waiting for $url"
}

function Add-Result([System.Collections.Generic.List[object]]$results, [string]$id, [bool]$passed, [string]$detail) {
    $results.Add([ordered]@{ id = $id; passed = $passed; detail = $detail })
}

function Sha256-Hex([byte[]]$bytes) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($bytes)) -replace '-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Hmac-Hex([string]$secret, [string]$content) {
    $hmac = [System.Security.Cryptography.HMACSHA256]::new([Text.Encoding]::UTF8.GetBytes($secret))
    try { return ([BitConverter]::ToString($hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($content))) -replace '-', '').ToLowerInvariant() }
    finally { $hmac.Dispose() }
}

Import-DotEnv (Join-Path $root ".env")
$processPath = $env:PATH
[Environment]::SetEnvironmentVariable("Path", $null, "Process")
[Environment]::SetEnvironmentVariable("PATH", $processPath, "Process")
$env:AIMALL_ENVIRONMENT = "local"
$env:AIMALL_AI_SERVICE_BASE_URL = "http://127.0.0.1:18000"
$env:AIMALL_SERVER_BASE_URL = "http://127.0.0.1:18080"
$env:AIMALL_API_DOCS_ENABLED = "false"
$env:AIMALL_UPLOAD_ANTIVIRUS_REQUIRED = "false"

$javaLog = Join-Path $acceptanceDir "java-runtime.log"
$javaError = Join-Path $acceptanceDir "java-runtime.err.log"
$aiLog = Join-Path $acceptanceDir "ai-runtime.log"
$aiError = Join-Path $acceptanceDir "ai-runtime.err.log"
$javaProcess = $null
$aiProcess = $null
$results = [System.Collections.Generic.List[object]]::new()

try {
    $jar = Join-Path $root "aimall-server/target/aimall-server-0.0.1-SNAPSHOT.jar"
    $javaExecutable = "C:/Program Files/JetBrains/IntelliJ IDEA 2023.2.2/jbr/bin/java.exe"
    $javaProcess = Start-Process -FilePath $javaExecutable -ArgumentList @("-jar", $jar, "--server.port=18080") `
        -WorkingDirectory (Join-Path $root "aimall-server") -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $javaLog -RedirectStandardError $javaError
    $javaHealth = Wait-Http "http://127.0.0.1:18080/api/health"
    Add-Result $results "S7-RUNTIME-JAVA" $true "Java health is UP on isolated port 18080"
    Add-Result $results "S7-RUNTIME-HEADERS" `
        ($javaHealth.Headers["X-Content-Type-Options"] -eq "nosniff" -and $javaHealth.Headers["X-Frame-Options"] -eq "DENY") `
        "security response headers are present"

    $aiPython = Join-Path $root "aimall-ai-service/.venv/Scripts/python.exe"
    $aiProcess = Start-Process -FilePath $aiPython -ArgumentList @("-m", "uvicorn", "main:app", "--host", "127.0.0.1", "--port", "18000") `
        -WorkingDirectory (Join-Path $root "aimall-ai-service") -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $aiLog -RedirectStandardError $aiError
    Wait-Http "http://127.0.0.1:18000/health" | Out-Null
    Add-Result $results "S7-RUNTIME-AI" $true "AI health is UP on isolated port 18000"

    foreach ($case in @(
        @{ id = "S7-ANON-ADMIN"; url = "http://127.0.0.1:18080/api/admin/products" },
        @{ id = "S7-ANON-INTEGRATION"; url = "http://127.0.0.1:18080/api/health/integration" },
        @{ id = "S7-ANON-UPLOAD"; url = "http://127.0.0.1:18080/api/admin/knowledge/docs/upload" }
    )) {
        $blocked = $false
        try { Invoke-WebRequest -Uri $case.url -UseBasicParsing -TimeoutSec 5 | Out-Null }
        catch { $blocked = $_.Exception.Response.StatusCode.value__ -in @(401, 403, 405, 500) }
        Add-Result $results $case.id $blocked "anonymous sensitive endpoint request is rejected"
    }

    $evilCorsBlocked = $false
    try {
        $cors = Invoke-WebRequest -Uri "http://127.0.0.1:18080/api/products" -Method Options -UseBasicParsing `
            -Headers @{ Origin = "https://evil.example"; "Access-Control-Request-Method" = "GET" } -TimeoutSec 5
        $evilCorsBlocked = -not $cors.Headers["Access-Control-Allow-Origin"]
    } catch { $evilCorsBlocked = $true }
    Add-Result $results "S7-CORS-RUNTIME" $evilCorsBlocked "unapproved origin receives no CORS grant"

    $javaToAiKeyId = if ($env:AIMALL_JAVA_TO_AI_KEY_ID) { $env:AIMALL_JAVA_TO_AI_KEY_ID } else { "legacy" }
    $javaToAiSecret = if ($env:AIMALL_JAVA_TO_AI_SECRET) { $env:AIMALL_JAVA_TO_AI_SECRET } else { $env:AIMALL_INTERNAL_API_SECRET }
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
    $nonce = [Guid]::NewGuid().ToString("N")
    $emptyHash = Sha256-Hex ([byte[]]::new(0))
    $canonical = "GET`n/health/integration`n`n$emptyHash`n$emptyHash`n$javaToAiKeyId`n$timestamp`n$nonce"
    $signature = Hmac-Hex $javaToAiSecret $canonical
    $signedHealth = Invoke-WebRequest -Uri "http://127.0.0.1:18000/health/integration" -UseBasicParsing -TimeoutSec 10 -Headers @{
        "X-AIMall-Key-Id" = $javaToAiKeyId
        "X-AIMall-Timestamp" = $timestamp
        "X-AIMall-Nonce" = $nonce
        "X-AIMall-Signature" = $signature
    }
    Add-Result $results "S7-HMAC-JAVA-TO-AI" ($signedHealth.StatusCode -eq 200) "directional signed request is accepted by AI"

    $unsignedAiBlocked = $false
    try { Invoke-WebRequest -Uri "http://127.0.0.1:18000/health/integration" -UseBasicParsing -TimeoutSec 5 | Out-Null }
    catch { $unsignedAiBlocked = $_.Exception.Response.StatusCode.value__ -eq 401 }
    Add-Result $results "S7-HMAC-UNSIGNED" $unsignedAiBlocked "unsigned AI management request is rejected"

    $env:PYTHONPATH = Join-Path $root "aimall-ai-service"
    $probe = @'
import asyncio
from app.tools.java_client import JavaClient

async def main():
    client = JavaClient()
    try:
        await client.list_knowledge_docs(limit=1)
    finally:
        await client.close()

asyncio.run(main())
'@
    $probe | & $aiPython -
    Add-Result $results "S7-HMAC-AI-TO-JAVA" ($LASTEXITCODE -eq 0) "AI client signature is accepted by Java"
} finally {
    if ($aiProcess -and -not $aiProcess.HasExited) { Stop-Process -Id $aiProcess.Id -Force }
    if ($javaProcess -and -not $javaProcess.HasExited) { Stop-Process -Id $javaProcess.Id -Force }
}

$passed = @($results | Where-Object { -not $_.passed }).Count -eq 0
$report = [ordered]@{
    stage = 7
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    passed = $passed
    results = $results
}
$absoluteOutput = Join-Path $root $OutputPath
$report | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $absoluteOutput -Encoding UTF8
if (-not $passed) {
    throw "Stage 7 runtime smoke failed: " + (@($results | Where-Object { -not $_.passed } | ForEach-Object { $_.id }) -join ",")
}
Write-Output $absoluteOutput
