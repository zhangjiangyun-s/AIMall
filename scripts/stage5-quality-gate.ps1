param([switch]$RequireEngineering, [string]$Output = '.acceptance/stage5/quality-gate.json')
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$python = Join-Path $root 'aimall-ai-service/.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { $python = (Get-Command python -ErrorAction Stop).Source }
$args = @((Join-Path $root 'tools/stage5_business_gate.py'), '--output', $Output)
if ($RequireEngineering) { $args += '--require-engineering' }
& $python @args
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
