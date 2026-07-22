@echo off
setlocal
cd /d "%~dp0"

set "PS_SCRIPT=%~dp0start-local.ps1"

if not exist "%PS_SCRIPT%" (
  echo start-local.ps1 not found.
  exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command ^
  "Start-Process -FilePath 'powershell.exe' -WorkingDirectory '%~dp0' -WindowStyle Hidden -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File','%PS_SCRIPT%')" >nul 2>nul

exit /b 0
