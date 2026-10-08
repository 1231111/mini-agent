@echo off
title MiniAgent Desktop (dev)

echo ========================================
echo   MiniAgent Desktop - 开发态启动
echo ========================================
echo.

REM 后端不再由本脚本启动。
REM main.js 会自己 spawn java -jar 拉起后端。这里再起一份会变成两个后端实例抢端口，
REM 而且老写法 mvn spring-boot:run 还要求本机装 Maven —— 那正是出厂形态要摆脱的东西。
if not exist "%~dp0..\mini-agent-app\target\mini-agent-app-0.0.1-SNAPSHOT.jar" (
    echo [ERROR] 找不到后端 jar。请先在仓库根目录执行：
    echo         mvnw.cmd package -pl mini-agent-app -am -DskipTests
    echo.
    pause
    exit /b 1
)

where node >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] 找不到 Node.js，请安装 Node.js 18+
    pause
    exit /b 1
)

cd /d "%~dp0"
if not exist "node_modules" (
    echo 首次运行，安装依赖...
    call npm install
)

echo 启动桌面客户端（后端由 main.js 负责拉起）...
call npm start

echo.
echo MiniAgent Desktop closed.
pause
