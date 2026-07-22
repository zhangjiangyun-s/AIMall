param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Container = "my-redis"
)

$ErrorActionPreference = "Stop"
$rootPath = [System.IO.Path]::GetFullPath($Root)
$evidence = Join-Path $rootPath ".acceptance/stage10"
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$context = Join-Path $evidence "redis-restart-context.json"
$result = Join-Path $evidence "real-redis.json"
$python = Join-Path $rootPath "aimall-ai-service/.venv/Scripts/python.exe"
$helper = Join-Path $rootPath "aimall-ai-service/scripts/stage10_redis_restart_drill.py"

$image = docker inspect $Container --format '{{.Config.Image}}'
if ($LASTEXITCODE -ne 0 -or $image -notmatch '^redis(?::|$)') {
    throw "Refusing to restart non-Redis container '$Container' (image '$image')."
}
$passwordLine = Get-Content (Join-Path $rootPath ".env") | Where-Object { $_ -match '^REDIS_PASSWORD=' } | Select-Object -First 1
if (-not $passwordLine) { throw "REDIS_PASSWORD is missing from .env." }
$password = ($passwordLine -split '=', 2)[1].Trim().Trim('"').Trim("'")
$env:REDIS_URL = "redis://:$([Uri]::EscapeDataString($password))@127.0.0.1:6379/0"
try {
    Push-Location (Join-Path $rootPath "aimall-ai-service")
    & $python $helper prepare --context $context
    if ($LASTEXITCODE -ne 0) { throw "Redis drill prepare failed." }
    docker restart $Container | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Redis container restart failed." }
    & $python $helper wait --context $context --timeout 30
    if ($LASTEXITCODE -ne 0) { throw "Redis did not recover." }
    & $python $helper verify --context $context --result $result
    if ($LASTEXITCODE -ne 0) { throw "Redis restart verification failed." }
} finally {
    Pop-Location -ErrorAction SilentlyContinue
    Remove-Item Env:REDIS_URL -ErrorAction SilentlyContinue
    Remove-Item $context -ErrorAction SilentlyContinue
}
Write-Output $result
