@echo off
setlocal EnableExtensions EnableDelayedExpansion
title MiniAgent Windows Package

cd /d "%~dp0.."

echo ========================================
echo   MiniAgent Windows one-click package
echo ========================================
echo.

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

rem Environment preparation (idempotent): JDK 21, Maven mirror, materials check.
rem Nothing is downloaded or changed on a machine that is already set up.
rem NOTE: a user-level variable created by setx is invisible to this process,
rem so the script writes the resolved JDK path to a temp file, read back below.
rem Keep this file ASCII-only: cmd parses .bat in the OEM code page, so non-ASCII
rem text gets mangled into broken commands.
if exist "%TEMP%\miniagent-jdk-path.txt" del /q "%TEMP%\miniagent-jdk-path.txt"
powershell -NoProfile -ExecutionPolicy Bypass -File "scripts\prepare-win.ps1"
if errorlevel 1 goto :failed
if exist "%TEMP%\miniagent-jdk-path.txt" set /p JAVA_HOME=<"%TEMP%\miniagent-jdk-path.txt"
if not defined JAVA_HOME (
    echo [ERROR] Could not determine the JDK 21 path.
    echo Set JAVA_HOME to a JDK root and retry, or run:
    echo     powershell -NoProfile -ExecutionPolicy Bypass -File scripts\prepare-win.ps1
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
rem Browser revisions must match the new fat jar. The build script validates/reuses
rem current material automatically; a directory from an older driver is insufficient.
if exist "mini-agent-desktop\models\yuan-embedding-2.0-zh.int8.onnx" (
    if exist "mini-agent-desktop\models\tokenizer.json" (
        set "BUILD_ARGS=!BUILD_ARGS! --skip-models"
    )
)

rem Electron / electron-builder fetch their binaries from GitHub releases, which is
rem unusably slow on many networks (measured ~15 KB/s vs ~1.5 MB/s from npmmirror).
rem Only defaults are set here - anything already defined by the caller wins, so a
rem CI runner or an overseas machine keeps using the official source.
if not defined ELECTRON_MIRROR set "ELECTRON_MIRROR=https://registry.npmmirror.com/-/binary/electron/"
if not defined ELECTRON_BUILDER_BINARIES_MIRROR set "ELECTRON_BUILDER_BINARIES_MIRROR=https://registry.npmmirror.com/-/binary/electron-builder-binaries/"

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
