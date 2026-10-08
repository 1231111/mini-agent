@echo off
REM 跑 RenderGuardProbe。第二个参数 stripped 时把 PATH 剥到只剩 System32，
REM 用来模拟"客户机装了 Node 之外什么都没有"。
REM
REM 为什么用 .cmd 而不是在 Git Bash 里 env PATH=... ：
REM MSYS 会改写在 exec 时传给子进程的 PATH（实测 C:\Windows\System32 被拼成了
REM <git-root>\Windows\System32 并前置了一个 "C;"），于是 where.exe 本身都找不到 ——
REM 那样测出来的是"探测工具丢了"，不是"where npx 返回 exit=1"，模拟不忠实。
REM cmd.exe 不碰 PATH，写进来是什么就是什么。必须在引号外调用（PowerShell 里用 & cmd /c）。

setlocal
set ROOT=D:\AI\miniagent
set "JAVA=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\java.exe"

if "%~2"=="stripped" (
  set "PATH=C:\Windows\System32"
)

set /p DEPS=<"%ROOT%\.verify\cp-tools.txt"
set "CP=%ROOT%\mini-agent-tools\target\classes;%DEPS%"
set "LOG=%ROOT%\.verify\render-guard-%~1.log"

"%JAVA%" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "%CP%" ^
  "%ROOT%\.verify\RenderGuardProbe.java" %~1 > "%LOG%" 2>&1
set RC=%ERRORLEVEL%

type "%LOG%"
echo.
echo exit=%RC%
endlocal
