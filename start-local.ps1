param(
    [ValidateSet("all", "server", "web", "admin", "ai")]
    [string]$Service = "all",

    [switch]$Child,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path

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

Import-EnvFile (Join-Path $Root ".env")

function Write-Section {
    param([string]$Text)
    Write-Host ""
    Write-Host "============================================================"
    Write-Host " $Text"
    Write-Host "============================================================"
}

function Test-CommandExists {
    param([string]$Name)
    return $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

function Require-Command {
    param(
        [string]$Name,
        [string]$Hint
    )

    if (-not (Test-CommandExists $Name)) {
        throw "Missing command '$Name'. $Hint"
    }
}

function Test-TcpPort {
    param(
        [string]$HostName,
        [int]$Port,
        [int]$TimeoutMs = 700
    )

    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $async.AsyncWaitHandle.WaitOne($TimeoutMs, $false)) {
            return $false
        }

        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Warn-IfPortBusy {
    param([int]$Port)

    $listeners = [System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()
    if ($listeners | Where-Object { $_.Port -eq $Port }) {
        Write-Host "[warn] Port $Port is already in use. If startup fails, close the old process first." -ForegroundColor Yellow
    }
}

function Ensure-NodeModules {
    param([string]$ProjectDir)

    $nodeModules = Join-Path $ProjectDir "node_modules"
    $viteCommand = Join-Path $nodeModules ".bin\vite.cmd"
    $viteEntry = Join-Path $nodeModules "vite\bin\vite.js"
    $dependenciesReady = (Test-Path $viteCommand) -and (Test-Path $viteEntry)

    if ($dependenciesReady) {
        Write-Host "[ok] Frontend dependencies are installed."
        return
    }

    if ($SkipInstall) {
        throw "Frontend dependencies are missing or incomplete in $ProjectDir, and -SkipInstall was used."
    }

    if (Test-Path $nodeModules) {
        Write-Host "[setup] node_modules is incomplete, repairing frontend dependencies..." -ForegroundColor Yellow
    } else {
        Write-Host "[setup] node_modules not found, installing frontend dependencies..."
    }

    Push-Location $ProjectDir
    try {
        & npm.cmd install --include=dev
        if ($LASTEXITCODE -ne 0) {
            throw "npm install failed in $ProjectDir with exit code $LASTEXITCODE."
        }
    } finally {
        Pop-Location
    }

    if (-not ((Test-Path $viteCommand) -and (Test-Path $viteEntry))) {
        throw "Frontend dependency repair completed, but Vite is still missing in $ProjectDir."
    }

    Write-Host "[ok] Frontend dependencies repaired."
}

function Get-PythonCommand {
    $python = Get-Command python -ErrorAction SilentlyContinue
    if ($python) {
        return @($python.Source)
    }

    $py = Get-Command py -ErrorAction SilentlyContinue
    if ($py) {
        return @($py.Source, "-3")
    }

    return @()
}

function Invoke-BasePython {
    param([string[]]$PythonArgs)

    if (-not $script:PythonCommand) {
        $script:PythonCommand = @(Get-PythonCommand)
    }

    if ($script:PythonCommand.Count -eq 0) {
        throw "Missing Python. Install Python 3.10+ and make sure python or py is available."
    }

    $exe = [string]$script:PythonCommand[0]
    $prefixArgs = @()
    if ($script:PythonCommand.Count -gt 1) {
        $prefixArgs = $script:PythonCommand[1..($script:PythonCommand.Count - 1)]
    }

    & $exe @prefixArgs @PythonArgs
}

function Run-Server {
    [Console]::Title = "aimall-server :8080"
    $project = Join-Path $Root "aimall-server"

    Write-Section "aimall-server"
    Require-Command "java" "Install JDK 17+."
    Require-Command "mvn" "Install Maven and add it to PATH."
    Warn-IfPortBusy 8080

    if (-not (Test-TcpPort "127.0.0.1" 3306)) {
        Write-Host "[warn] MySQL 127.0.0.1:3306 is not reachable. The backend may fail until MySQL is running." -ForegroundColor Yellow
        Write-Host "       Docker MySQL is OK too, as long as it maps to localhost:3306."
    }

    Push-Location $project
    try {
        $env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=UTF-8"
        $env:MAVEN_OPTS = "-Dfile.encoding=UTF-8"
        Write-Host "[run] Backend URL: http://localhost:8080"
        mvn spring-boot:run
    } finally {
        Pop-Location
    }
}

function Run-Web {
    [Console]::Title = "aimall-web :5173"
    $project = Join-Path $Root "aimall-web"

    Write-Section "aimall-web"
    Require-Command "node" "Install Node.js 22+."
    Require-Command "npm" "Install npm with Node.js."
    Warn-IfPortBusy 5173
    Ensure-NodeModules $project

    Push-Location $project
    try {
        Write-Host "[run] User frontend URL: http://localhost:5173"
        npm run dev -- --host 0.0.0.0 --port 5173
    } finally {
        Pop-Location
    }
}

function Run-Admin {
    [Console]::Title = "aimall-admin :5174"
    $project = Join-Path $Root "aimall-admin"

    Write-Section "aimall-admin"
    Require-Command "node" "Install Node.js 22+."
    Require-Command "npm" "Install npm with Node.js."
    Warn-IfPortBusy 5174
    Ensure-NodeModules $project

    Push-Location $project
    try {
        Write-Host "[run] Admin frontend URL: http://localhost:5174"
        npm run dev -- --host 0.0.0.0 --port 5174
    } finally {
        Pop-Location
    }
}

function Run-Ai {
    [Console]::Title = "aimall-ai-service :8000"
    $project = Join-Path $Root "aimall-ai-service"
    $venvPython = Join-Path $project ".venv\Scripts\python.exe"
    $requirements = Join-Path $project "requirements.txt"
    $installMarker = Join-Path $project ".venv\.requirements-installed"

    Write-Section "aimall-ai-service"
    Warn-IfPortBusy 8000

    Push-Location $project
    try {
        if (-not (Test-Path $venvPython)) {
            if ($SkipInstall) {
                throw "Python virtual environment is missing, and -SkipInstall was used."
            }

            Write-Host "[setup] Creating Python virtual environment..."
            Invoke-BasePython @("-m", "venv", ".venv")
        }

        $needsInstall = -not (Test-Path $installMarker)
        if ((Test-Path $requirements) -and (Test-Path $installMarker)) {
            $needsInstall = (Get-Item $requirements).LastWriteTime -gt (Get-Item $installMarker).LastWriteTime
        }

        if ($needsInstall) {
            if ($SkipInstall) {
                throw "Python packages need installation, and -SkipInstall was used."
            }

            Write-Host "[setup] Installing Python packages..."
            & $venvPython -m pip install -r requirements.txt
            New-Item -ItemType File -Path $installMarker -Force | Out-Null
        } else {
            Write-Host "[ok] Python packages already installed."
        }

        Write-Host "[run] AI service URL: http://localhost:8000"
        & $venvPython -m uvicorn main:app --reload --host 0.0.0.0 --port 8000
    } finally {
        Pop-Location
    }
}

function Start-ServiceWindow {
    param(
        [string]$Name,
        [string]$Title
    )

    $powershell = (Get-Command powershell.exe).Source
    $args = @(
        "-NoExit",
        "-ExecutionPolicy", "Bypass",
        "-File", $PSCommandPath,
        "-Service", $Name,
        "-Child"
    )

    if ($SkipInstall) {
        $args += "-SkipInstall"
    }

    Write-Host "[start] $Title"
    Start-Process -FilePath $powershell -WorkingDirectory $Root -ArgumentList $args
}

function Test-PreflightForAll {
    $missing = @()

    foreach ($name in @("java", "mvn", "node", "npm")) {
        if (-not (Test-CommandExists $name)) {
            $missing += $name
        }
    }

    if (@(Get-PythonCommand).Count -eq 0) {
        $missing += "python"
    }

    if ($missing.Count -gt 0) {
        Write-Host "[error] Missing required tools: $($missing -join ', ')" -ForegroundColor Red
        Write-Host "Install JDK 17+, Maven, Node.js 22+, and Python 3.10+ before starting all services."
        exit 1
    }
}

function Start-All {
    Write-Section "AIMall local startup"
    Test-PreflightForAll

    if (-not (Test-TcpPort "127.0.0.1" 3306)) {
        Write-Host "[warn] MySQL 127.0.0.1:3306 is not reachable. Start local MySQL or Docker MySQL first." -ForegroundColor Yellow
    }

    Start-ServiceWindow "server" "Backend server        http://localhost:8080"
    Start-Sleep -Seconds 2
    Start-ServiceWindow "web" "User frontend        http://localhost:5173"
    Start-ServiceWindow "admin" "Admin frontend       http://localhost:5174"
    Start-ServiceWindow "ai" "AI service           http://localhost:8000"

    Write-Host ""
    Write-Host "All four service windows were opened."
    Write-Host "User frontend : http://localhost:5173"
    Write-Host "Admin frontend: http://localhost:5174"
    Write-Host "Backend       : http://localhost:8080"
    Write-Host "AI service    : http://localhost:8000"
    Write-Host ""
    Write-Host "Tip: keep those windows open while developing. Close a window to stop that service."
}

if ($Child) {
    switch ($Service) {
        "server" { Run-Server }
        "web" { Run-Web }
        "admin" { Run-Admin }
        "ai" { Run-Ai }
        default { throw "Child mode needs Service=server/web/admin/ai." }
    }
} else {
    switch ($Service) {
        "all" { Start-All }
        "server" { Start-ServiceWindow "server" "Backend server        http://localhost:8080" }
        "web" { Start-ServiceWindow "web" "User frontend        http://localhost:5173" }
        "admin" { Start-ServiceWindow "admin" "Admin frontend       http://localhost:5174" }
        "ai" { Start-ServiceWindow "ai" "AI service           http://localhost:8000" }
    }
}
