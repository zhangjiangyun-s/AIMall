param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Container = "milvus-standalone"
)

$ErrorActionPreference = "Stop"
$rootPath = [System.IO.Path]::GetFullPath($Root)
$evidence = Join-Path $rootPath ".acceptance/stage10"
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$context = Join-Path $evidence "milvus-fault-context.json"
$result = Join-Path $evidence "real-milvus.json"
$python = Join-Path $rootPath "aimall-ai-service/.venv/Scripts/python.exe"
$helper = Join-Path $rootPath "aimall-ai-service/scripts/stage10_milvus_fault_drill.py"

$image = docker inspect $Container --format '{{.Config.Image}}'
if ($LASTEXITCODE -ne 0 -or $image -notmatch '^milvusdb/milvus:') {
    throw "Refusing to pause non-Milvus container '$Container' (image '$image')."
}
$stopped = $false
try {
    Push-Location (Join-Path $rootPath "aimall-ai-service")
    & $python $helper prepare --context $context
    if ($LASTEXITCODE -ne 0) { throw "Milvus drill prepare failed." }
    docker stop --time 10 $Container | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Milvus stop failed." }
    $stopped = $true
    & $python $helper outage --context $context
    if ($LASTEXITCODE -ne 0) { throw "Milvus outage was not detected." }
    docker start $Container | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Milvus start failed." }
    $stopped = $false
    Start-Sleep -Seconds 2
    & $python $helper verify --context $context --result $result
    if ($LASTEXITCODE -ne 0) { throw "Milvus recovery verification failed." }
} finally {
    $state = (docker inspect $Container --format '{{.State.Status}}' 2>$null).Trim()
    if ($stopped -or $state -ne "running") { docker start $Container | Out-Null }
    Pop-Location -ErrorAction SilentlyContinue
    Remove-Item $context -ErrorAction SilentlyContinue
}
Write-Output $result
