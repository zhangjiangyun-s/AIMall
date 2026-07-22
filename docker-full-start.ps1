param([switch]$Tunnel)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$composeFile = Join-Path $root "docker-compose.full.yml"
$envFile = Join-Path $root ".env.docker.local"

if (-not (Test-Path $envFile)) {
    throw ".env.docker.local is missing. Create it from .env.docker.example first."
}

$arguments = @(
    "compose",
    "--env-file", $envFile,
    "-f", $composeFile
)

if ($Tunnel) {
    $arguments += @("--profile", "tunnel")
}

$arguments += @("up", "-d", "--build")
& docker @arguments
if ($LASTEXITCODE -ne 0) {
    throw "Docker full-stack startup failed with exit code $LASTEXITCODE."
}

& docker compose --env-file $envFile -f $composeFile ps

Write-Host ""
Write-Host "AIMall Docker stack is starting:"
Write-Host "  Storefront : http://localhost:15173"
Write-Host "  Admin      : http://localhost:15174"
Write-Host "  Backend    : http://localhost:18080/api/health"
Write-Host "  AI         : http://localhost:18000/health"
Write-Host "  MailHog    : http://localhost:18025"
Write-Host "  Grafana    : http://localhost:13000"
