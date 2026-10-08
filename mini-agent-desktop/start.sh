#!/usr/bin/env bash
#
# MiniAgent Desktop 开发态启动（macOS / Linux）。
# 与 start.bat 等价 —— 那边是 Windows 版，两边的职责必须保持一致：
# 只检查前置条件 + 拉起 Electron，后端由 main.js 自己 spawn。
#
# 用法：
#   ./start.sh
#
set -euo pipefail

cd "$(dirname "$0")"

echo "========================================"
echo "  MiniAgent Desktop - 开发态启动"
echo "========================================"
echo

JAR="../mini-agent-app/target/mini-agent-app-0.0.1-SNAPSHOT.jar"

# 后端不由本脚本启动：main.js 会自己 spawn java -jar。这里再起一份会变成两个实例抢端口，
# 而且老写法 mvn spring-boot:run 还要求本机装 Maven —— 那正是出厂形态要摆脱的东西。
if [ ! -f "$JAR" ]; then
  echo "[ERROR] 找不到后端 jar。请先在仓库根目录执行：" >&2
  echo "        ./mvnw package -pl mini-agent-app -am -DskipTests" >&2
  echo >&2
  exit 1
fi

if ! command -v node >/dev/null 2>&1; then
  echo "[ERROR] 找不到 Node.js，请安装 Node.js 18+" >&2
  exit 1
fi

# 开发态用的是 PATH 上的 java。jlink 那套随包 JRE 只在打包产物里生效，
# 所以这里要确认 java 在，否则 main.js 起后端时会报一个不好定位的 spawn 错误。
if ! command -v java >/dev/null 2>&1; then
  echo "[ERROR] 找不到 java，需要 JDK 21+（后端是 Spring Boot 应用）" >&2
  exit 1
fi

if [ ! -d node_modules ]; then
  echo "首次运行，安装依赖..."
  npm install
fi

echo "启动桌面客户端（后端由 main.js 负责拉起）..."
npm start

echo
echo "MiniAgent Desktop closed."
