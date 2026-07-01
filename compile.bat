@echo off
cd /d D:\project\mini-agent-springboot
echo === Cleaning ===
call mvn clean -q 2>&1
echo === Compiling ===
call mvn compile 2>&1
echo === DONE ===
