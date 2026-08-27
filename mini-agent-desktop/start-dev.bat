@echo off
title MiniAgent Desktop - Dev Mode

echo ========================================
echo   MiniAgent Desktop (Development)
echo ========================================
echo.

set NODE_ENV=development

echo [Backend] Starting Spring Boot...
cd /d "%~dp0\.."
start "MiniAgent Backend" cmd /k "mvn spring-boot:run -pl mini-agent-app"

echo [Backend] Waiting for startup...
timeout /t 15 /nobreak >nul

echo [Desktop] Starting Electron (Dev Mode)...
cd /d "%~dp0"
if not exist "node_modules" (
    echo Installing dependencies...
    call npm install
)
call npm run dev

echo.
echo Development mode exited.
pause
