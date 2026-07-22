param(
    [ValidateSet("auto", "local", "docker")]
    [string]$Mode = "auto",

    [string]$Host = "127.0.0.1",
    [int]$Port = 3306,
    [string]$User = "root",
    [string]$Password,
    [string]$Database = "aimall",
    [string]$ContainerName = "aimall-docker-mysql-1",
    [switch]$ConfirmReset
)

$ErrorActionPreference = "Stop"
$Root = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$SchemaPath = Join-Path $Root "aimall-server\src\main\resources\schema.sql"
$DataPath = Join-Path $Root "aimall-server\src\main\resources\data.sql"

function Import-EnvFile {
    param([string]$Path)

    if (-not (Test-Path $Path)) {
        return
    }

    Get-Content -Path $Path | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#") -or -not $line.Contains("=")) {
            return
        }

        $parts = $line.Split("=", 2)
        $name = $parts[0].Trim()
        $value = $parts[1].Trim().Trim('"').Trim("'")
        if ($name) {
            [Environment]::SetEnvironmentVariable($name, $value, "Process")
        }
    }
}

function Test-CommandExists {
    param([string]$Name)
    return $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

function New-BootstrapSqlFile {
    param(
        [string]$SchemaFile,
        [string]$DataFile,
        [string]$DatabaseName
    )

    $tempFile = Join-Path ([System.IO.Path]::GetTempPath()) ("aimall-reset-" + [System.Guid]::NewGuid() + ".sql")
    $schemaContent = Get-Content -Path $SchemaFile -Raw -Encoding UTF8
    $dataContent = Get-Content -Path $DataFile -Raw -Encoding UTF8

    @"
CREATE DATABASE IF NOT EXISTS $DatabaseName DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE $DatabaseName;
SET NAMES utf8mb4;
$schemaContent

$dataContent
"@ | Set-Content -Path $tempFile -Encoding UTF8

    return $tempFile
}

function Invoke-LocalMysqlReset {
    param(
        [string]$SqlFile,
        [string]$HostName,
        [int]$PortNumber,
        [string]$UserName,
        [string]$Pwd
    )

    $mysqlCommand = Get-Command mysql -ErrorAction SilentlyContinue
    if (-not $mysqlCommand) {
        throw "mysql command not found. Install MySQL Client or use -Mode docker."
    }

    Write-Host "[reset] Using local mysql client..." -ForegroundColor Cyan
    Get-Content -Path $SqlFile -Raw | & $mysqlCommand.Source "--host=$HostName" "--port=$PortNumber" "--user=$UserName" "--default-character-set=utf8mb4" "-p$Pwd"
}

function Invoke-DockerMysqlReset {
    param(
        [string]$SqlFile,
        [string]$DockerContainer,
        [string]$UserName,
        [string]$Pwd
    )

    if (-not (Test-CommandExists "docker")) {
        throw "docker command not found."
    }

    $running = docker ps --format "{{.Names}}" | Where-Object { $_ -eq $DockerContainer }
    if (-not $running) {
        throw "Docker container '$DockerContainer' is not running."
    }

    Write-Host "[reset] Using Docker MySQL container..." -ForegroundColor Cyan
    Get-Content -Path $SqlFile -Raw | docker exec -i $DockerContainer mysql "--user=$UserName" "--default-character-set=utf8mb4" "-p$Pwd"
}

Import-EnvFile (Join-Path $Root ".env")

if (-not $ConfirmReset) {
    throw "Database reset is destructive. Re-run with -ConfirmReset after verifying the target."
}

if ($env:AIMALL_ENVIRONMENT -match '^(prod|production)$') {
    throw "reset-local-db.ps1 cannot run when AIMALL_ENVIRONMENT is production."
}

if (-not $Password) {
    $Password = $env:AIMALL_DB_PASSWORD
}
if (-not $Password) {
    $Password = $env:MYSQL_ROOT_PASSWORD
}
if (-not $Password) {
    throw "Database password is required through -Password, AIMALL_DB_PASSWORD, or MYSQL_ROOT_PASSWORD."
}

if (-not (Test-Path $SchemaPath)) {
    throw "schema.sql not found: $SchemaPath"
}
if (-not (Test-Path $DataPath)) {
    throw "data.sql not found: $DataPath"
}

$sqlFile = New-BootstrapSqlFile -SchemaFile $SchemaPath -DataFile $DataPath -DatabaseName $Database

try {
    switch ($Mode) {
        "local" {
            Invoke-LocalMysqlReset -SqlFile $sqlFile -HostName $Host -PortNumber $Port -UserName $User -Pwd $Password
        }
        "docker" {
            Invoke-DockerMysqlReset -SqlFile $sqlFile -DockerContainer $ContainerName -UserName $User -Pwd $Password
        }
        default {
            if (Test-CommandExists "mysql") {
                Invoke-LocalMysqlReset -SqlFile $sqlFile -HostName $Host -PortNumber $Port -UserName $User -Pwd $Password
            } else {
                Invoke-DockerMysqlReset -SqlFile $sqlFile -DockerContainer $ContainerName -UserName $User -Pwd $Password
            }
        }
    }

    Write-Host ""
    Write-Host "[ok] Database '$Database' has been rebuilt from schema.sql and data.sql." -ForegroundColor Green
    Write-Host "     You can restart aimall-server now."
} finally {
    if (Test-Path $sqlFile) {
        Remove-Item -LiteralPath $sqlFile -Force
    }
}
