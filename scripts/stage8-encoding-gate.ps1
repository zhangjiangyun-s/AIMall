param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Output = ".acceptance/stage8/encoding-gate.json"
)

$ErrorActionPreference = "Stop"
$rootPath = [System.IO.Path]::GetFullPath($Root)
$outputPath = [System.IO.Path]::GetFullPath((Join-Path $rootPath $Output))
$strictUtf8 = [System.Text.UTF8Encoding]::new($false, $true)
$extensions = @(
    ".java", ".py", ".ts", ".tsx", ".vue", ".js", ".mjs", ".cjs",
    ".json", ".yml", ".yaml", ".xml", ".sql", ".md", ".properties",
    ".env.example", ".ps1", ".sh", ".css", ".scss", ".html"
)
$excludedSegments = @(
    "\.git\", "\target\", "\node_modules\", "\dist\", "\build\",
    "\.idea\", "\.acceptance\", "\coverage\", "\__pycache__\",
    "\.pytest_cache\", "\.mypy_cache\", "\.pydeps", "\.venv\", "\venv\"
)
$mojibakePatterns = @(
    '\uFFFD',
    '\u951F\u65A4\u62F7',
    '\u00EF\u00BF\u00BD',
    '\u00E2\u20AC\u2122',
    '\u00E2\u20AC\u0153',
    '\u00E2\u20AC\u009D',
    '\u93C2\u56E8\u6BDB',
    '\u9439\u30E8\u7627\u6434',
    '\u7487\u950B\u7730'
)

$invalidUtf8 = [System.Collections.Generic.List[object]]::new()
$mojibake = [System.Collections.Generic.List[object]]::new()
$scanned = 0

Get-ChildItem -LiteralPath $rootPath -Recurse -File -ErrorAction SilentlyContinue | ForEach-Object {
    $path = $_.FullName
    $normalized = $path.Replace('/', '\')
    if ($excludedSegments | Where-Object { $normalized.Contains($_) }) {
        return
    }
    $extension = $_.Extension.ToLowerInvariant()
    if (($extensions -notcontains $extension) -and $_.Name -ne ".env.example") {
        return
    }

    $scanned++
    $relative = $path.Substring($rootPath.TrimEnd('\', '/').Length).TrimStart('\', '/').Replace('\', '/')
    try {
        $text = $strictUtf8.GetString([System.IO.File]::ReadAllBytes($path))
    } catch {
        $invalidUtf8.Add([ordered]@{
            file = $relative
            error = $_.Exception.Message
        })
        return
    }

    foreach ($pattern in $mojibakePatterns) {
        if ([System.Text.RegularExpressions.Regex]::IsMatch($text, $pattern)) {
            $mojibake.Add([ordered]@{
                file = $relative
                pattern = $pattern
            })
        }
    }
}

$result = [ordered]@{
    gate = "stage8-encoding"
    generatedAt = [DateTimeOffset]::Now.ToString("o")
    root = $rootPath
    scannedFiles = $scanned
    invalidUtf8Count = $invalidUtf8.Count
    mojibakeCount = $mojibake.Count
    passed = ($invalidUtf8.Count -eq 0 -and $mojibake.Count -eq 0)
    invalidUtf8 = $invalidUtf8
    mojibake = $mojibake
}

[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($outputPath)) | Out-Null
[System.IO.File]::WriteAllText(
    $outputPath,
    ($result | ConvertTo-Json -Depth 8),
    [System.Text.UTF8Encoding]::new($false)
)

if (-not $result.passed) {
    Write-Error "Stage 8 encoding gate failed. See $outputPath"
}

Write-Output ($result | ConvertTo-Json -Depth 8)
