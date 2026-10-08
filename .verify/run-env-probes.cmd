@echo off
REM 三个场景各跑一次 EnvToolProbe。必须在对应的工作目录下跑，
REM 因为 ProcessBuilder 继承 JVM 的 cwd，而被测的正是"当前目录是不是 git 仓库"。
REM
REM 前置：先 ./mvnw -B compile -DskipTests -pl mini-agent-tools
REM      并确认 .verify/cp-tools.txt 已生成（./mvnw -B -q dependency:build-classpath
REM      -Dmdep.outputFile=cp-tools.txt -pl mini-agent-tools，注意它会落在模块目录下）

setlocal
set ROOT=D:\AI\miniagent
set CP=%ROOT%\mini-agent-tools\target\classes
set /p DEPS=<%ROOT%\.verify\cp-tools.txt
set CP=%CP%;%DEPS%
set PROBE=%ROOT%\.verify\EnvToolProbe.java

echo.
echo ################ 场景 1/3: git-repo（有提交 + 2 个未跟踪文件）################
cd /d %ROOT%\.verify\gitprobe
java -Dstdout.encoding=UTF-8 -cp "%CP%" "%PROBE%" git-repo > "%ROOT%\.verify\env-probe-gitrepo.log" 2>&1
type "%ROOT%\.verify\env-probe-gitrepo.log"

echo.
echo ################ 场景 2/3: git-repo-empty（刚 init、零提交）################
cd /d %ROOT%\.verify\gitprobe-empty
java -Dstdout.encoding=UTF-8 -cp "%CP%" "%PROBE%" git-repo-empty > "%ROOT%\.verify\env-probe-gitempty.log" 2>&1
type "%ROOT%\.verify\env-probe-gitempty.log"

echo.
echo ################ 场景 3/3: non-git（不在任何 git 仓库内）################
cd /d %TEMP%\miniagent-nongit-probe
java -Dstdout.encoding=UTF-8 -cp "%CP%" "%PROBE%" non-git > "%ROOT%\.verify\env-probe-nongit.log" 2>&1
type "%ROOT%\.verify\env-probe-nongit.log"

echo.
echo 完成。三份日志在 %ROOT%\.verify\env-probe-*.log
endlocal
