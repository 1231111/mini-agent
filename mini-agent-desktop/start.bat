@echo off
title MiniAgent Desktop

echo ========================================
echo   MiniAgent Desktop Launcher
echo ========================================
echo.

REM Check Java
where java >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Java not found. Please install JDK 21+
    pause
    exit /b 1
)

REM Check Node.js
where node >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Node.js not found. Please install Node.js 18+
    pause
    exit /b 1
)

echo [1/3] Starting backend server...
cd /d "%~dp0\.."
start "MiniAgent Backend" cmd /k "mvn spring-boot:run -pl mini-agent-app"

echo [2/3] Waiting for backend to start...
timeout /t 15 /nobreak >nul

echo [3/3] Starting desktop app...
cd /d "%~dp0"
if not exist "node_modules" (
    echo First run, installing dependencies...
    call npm install
)
call npm start

echo.
echo MiniAgent Desktop closed.
pause
