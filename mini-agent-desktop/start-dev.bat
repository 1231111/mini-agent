@echo off
title MiniAgent Desktop - Dev Mode

echo ========================================
echo   MiniAgent Desktop (Development)
echo ========================================
echo.

set NODE_ENV=development

REM 后端由 main.js 负责拉起（spawn java -jar），这里不要再起一份 ——
REM 两份会抢同一个端口，而且老写法 mvn spring-boot:run 还要求本机装 Maven。
if not exist "%~dp0..\mini-agent-app\target\mini-agent-app-0.0.1-SNAPSHOT.jar" (
    echo [ERROR] 找不到后端 jar。先在仓库根目录执行：
    echo         mvnw.cmd package -pl mini-agent-app -am -DskipTests
    pause
    exit /b 1
)

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
