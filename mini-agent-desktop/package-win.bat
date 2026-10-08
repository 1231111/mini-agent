@echo off
setlocal EnableExtensions EnableDelayedExpansion
title MiniAgent Windows Package

cd /d "%~dp0.."

where node >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Node.js 18+ was not found.
    echo Install Node.js, then run this script again.
    goto :failed
)

tasklist /FI "IMAGENAME eq MiniAgent.exe" /NH 2>nul ^
    | find /I "MiniAgent.exe" >nul
if not errorlevel 1 (
    echo [ERROR] MiniAgent is currently running.
    echo Exit it from the system tray, then run this script again.
    goto :failed
)

set "BUILD_ARGS=--target=win --expect-arch=x64"

rem Reuse large platform artifacts when a previous full build prepared them.
if exist "mini-agent-desktop\jre\bin\java.exe" (
    set "BUILD_ARGS=!BUILD_ARGS! --skip-jre"
)
if exist "mini-agent-desktop\ffmpeg\ffmpeg.dll" (
    set "BUILD_ARGS=!BUILD_ARGS! --skip-ffmpeg"
)
if exist "mini-agent-desktop\browsers\chromium_headless_shell-*" (
    set "BUILD_ARGS=!BUILD_ARGS! --skip-browsers"
)
if exist "mini-agent-desktop\models\yuan-embedding-2.0-zh.int8.onnx" (
    if exist "mini-agent-desktop\models\tokenizer.json" (
        set "BUILD_ARGS=!BUILD_ARGS! --skip-models"
    )
)

echo ========================================
echo   MiniAgent Windows one-click package
echo ========================================
echo.
echo Build arguments: !BUILD_ARGS!
echo.

node scripts\build-desktop.mjs !BUILD_ARGS!
if errorlevel 1 (
    goto :failed
)

echo.
echo [OK] Package created in:
echo      %~dp0dist
echo.
pause
exit /b 0

:failed
echo.
echo [ERROR] Packaging failed. Review the message above.
echo.
pause
exit /b 1
