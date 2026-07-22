param(
    [switch]$RequireEngineering,
    [switch]$RequireProduction,
    [string]$Output = '.acceptance/stage25/quality-gate.json'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$python = Join-Path $root 'aimall-ai-service/.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    $python = (Get-Command python -ErrorAction Stop).Source
}

$arguments = @(
    (Join-Path $root 'tools/stage25_v12_gate.py'),
    '--output', $Output
)
if ($RequireEngineering) { $arguments += '--require-engineering' }
if ($RequireProduction) { $arguments += '--require-production' }

& $python @arguments
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
