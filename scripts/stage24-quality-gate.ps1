param(
    [switch]$RequireEngineering,
    [switch]$RequireProduction,
    [string]$Output = '.acceptance/stage24/quality-gate.json'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$python = Join-Path $root 'aimall-ai-service/.venv/Scripts/python.exe'
if (-not (Test-Path -LiteralPath $python -PathType Leaf)) { $python = (Get-Command python -ErrorAction Stop).Source }

& $python (Join-Path $root 'tools/stage24_reflection_gate.py') --require-engineering | Out-Null
$result = Get-Content -LiteralPath (Join-Path $root '.acceptance/stage24/reflection-gate.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$outputPath = Join-Path $root $Output
[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($outputPath)) | Out-Null
[IO.File]::WriteAllText($outputPath, ($result | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
$result | ConvertTo-Json -Depth 20
if ($RequireEngineering -and -not $result.engineeringPassed) { throw "Stage 24 engineering gate failed: $outputPath" }
if ($RequireProduction -and -not $result.productionPassed) { throw "Stage 24 production gate failed: $outputPath" }
