param(
    [int]$Port = 8081
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

Get-Content (Join-Path $root ".env") | ForEach-Object {
    $line = $_.Trim()
    if (-not $line -or $line.StartsWith("#") -or -not $line.Contains("=")) { return }
    $parts = $line.Split("=", 2)
    [Environment]::SetEnvironmentVariable(
        $parts[0].Trim(), $parts[1].Trim().Trim('"').Trim("'"), "Process")
}

$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $mavenCommand) { $mavenCommand = Get-Command mvn -ErrorAction Stop }
$maven = $mavenCommand.Source

$env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"
$env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"
Set-Location (Join-Path $root "aimall-server")
& $maven spring-boot:run "-Dspring-boot.run.arguments=--server.port=$Port"
