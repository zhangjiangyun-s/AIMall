@echo off
setlocal
cd /d "%~dp0"
docker compose --env-file .env.docker.local -f docker-compose.full.yml down
exit /b %errorlevel%
