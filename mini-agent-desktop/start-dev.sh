#!/usr/bin/env bash
#
# MiniAgent Desktop 开发态启动（macOS / Linux），带 devtools。
# 与 start-dev.bat 等价。
#
# 用法：
#   ./start-dev.sh
#
set -euo pipefail

cd "$(dirname "$0")"

echo "========================================"
echo "  MiniAgent Desktop (Development)"
echo "========================================"
echo

export NODE_ENV=development

JAR="../mini-agent-app/target/mini-agent-app-0.0.1-SNAPSHOT.jar"

# 后端由 main.js 负责拉起（spawn java -jar），这里不要再起一份 ——
# 两份会抢同一个端口，而且老写法 mvn spring-boot:run 还要求本机装 Maven。
if [ ! -f "$JAR" ]; then
  echo "[ERROR] 找不到后端 jar。先在仓库根目录执行：" >&2
  echo "        ./mvnw package -pl mini-agent-app -am -DskipTests" >&2
  exit 1
fi

echo "[Desktop] Starting Electron (Dev Mode)..."
if [ ! -d node_modules ]; then
  echo "Installing dependencies..."
  npm install
fi
npm run dev

echo
echo "Development mode exited."
