param(
    [switch]$RequireEngineering,
    [switch]$RequireRelease,
    [string]$Output = '.acceptance/stage26/final-gate.json'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$python = Join-Path $root 'aimall-ai-service/.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
    $python = (Get-Command python -ErrorAction Stop).Source
}
$arguments = @((Join-Path $root 'tools/stage26_final_gate.py'), '--output', $Output)
if ($RequireEngineering) { $arguments += '--require-engineering' }
if ($RequireRelease) { $arguments += '--require-release' }
& $python @arguments
exit $LASTEXITCODE
