#!/usr/bin/env bash
#
# 启动 MiniAgent 应用（jar 独立于中间件运行）。
#
#   ./scripts/run-app.sh                  # 前台启动，Ctrl-C 停
#   ./scripts/run-app.sh --daemon         # 后台启动，日志到 ~/.miniagent/logs/app.log
#   ./scripts/run-app.sh --port 8080      # 指定端口（默认 8080）
#
# 中间件（MySQL / Redis）在宿主机上直接安装并运行，不由本脚本拉起。
# 只校验端口是否在监听，不假设任何容器运行时。
#
# 本脚本只负责三件事：校验必需变量 → 确认中间件可达 → 起 java。
# 它不改任何配置，所有值来自环境变量 + 仓库根的 .env + ~/.miniagent/config.yml。
#
# 为什么用 prod 档而不是 desktop 档：
#   desktop 档绑 127.0.0.1、用 H2 文件库、排除 Redis 自动配置 —— 它是给
#   Electron 客户端用的单机形态。要在服务器上给多人访问，必须走 prod 档：
#   MySQL 真相源 + Redis 分布式状态 + Flyway 校验表结构。
#   代价是 prod 档有一组启动断言（ProductionReadinessValidator），缺一个就拒绝启动，
#   所以下面每个变量都不是可选的。
#
# 向量与 embedding 走本地：
#   省掉 milvus / etcd / minio / embedding-server 四个容器。
#   agent.memory.vector.backend=local        → 本地 JSON 索引
#   agent.codebase.embedding-provider=local-onnx → 进程内 ONNX 推理
#   单实例部署完全够用；只有要多副本共享向量时才需要 Milvus。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

PORT=8080
DAEMON=0
JAR="mini-agent-app/target/mini-agent-app-0.0.1-SNAPSHOT.jar"

while [ $# -gt 0 ]; do
  case "$1" in
    --port)   PORT="$2"; shift 2 ;;
    --daemon) DAEMON=1; shift ;;
    --jar)    JAR="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

# ── 1. 读中间件口令 ──────────────────────────────────────────────
# .env 是部署机上的一份本地变量文件（已在 .gitignore 里），这里直接复用，避免同一口令写两处。
if [ -f .env ]; then
  # set -a 让读进来的变量自动 export 给子进程；注释行与空行会被 shell 正确处理。
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

missing=0
require() {
  local name="$1" hint="$2"
  local value="${!name:-}"
  if [ -z "$value" ]; then
    echo "缺少 $name —— $hint" >&2
    missing=1
  fi
}
require MYSQL_ROOT_PASSWORD "在 .env 里设置，与宿主机 MySQL 的 root 口令一致"
require REDIS_PASSWORD       "在 .env 里设置，与宿主机 Redis 的 requirepass 一致"
# 下面两项只有 prod 断言在读，且读的是 Environment.getProperty(key) ——
# 键名必须与断言里的一致。MIMO_API_KEY 顶替不了 LLM_API_KEY：前者喂给
# langchain4j 客户端，后者是断言单独检查的那一个键。
require MODEL_CONFIG_ENCRYPTION_KEY "32 字节 Base64（openssl rand -base64 32），用于加密用户填的模型配置"
require LLM_API_KEY                "对话模型密钥，可直接用 MIMO_API_KEY 的值：export LLM_API_KEY=..."
if [ "$missing" -eq 1 ]; then
  echo "修好上面的项再启动。" >&2
  exit 1
fi

# JWT_SECRET 通常已经在 ~/.miniagent/config.yml 里，Spring 会通过
# spring.config.import 把它作为属性加载；这里只在两边都没有时提示。
if [ -z "${JWT_SECRET:-}" ] && ! grep -q '^JWT_SECRET:' "$HOME/.miniagent/config.yml" 2>/dev/null; then
  echo "警告: 没有 JWT_SECRET（环境变量与 ~/.miniagent/config.yml 都没有）。" >&2
  echo "      prod 断言要求它至少 32 字节，否则拒绝启动：openssl rand -base64 48" >&2
fi

# ── 2. 组装 prod 档需要的变量 ────────────────────────────────────
# JWT_SECRET / LLM_API_KEY 等密钥可以从 ~/.miniagent/config.yml 来（Spring 的
# spring.config.import 会加载它），但断言读的是 Environment 属性，两处都认。
# 这里只在环境变量缺失时提示，不去解析 config.yml —— 解析错了会静默用空值。

# 模型目录按实际布局回退：构建产物在 mini-agent-desktop/models，
# 服务器上通常单独放一份（用 MINI_AGENT_MODELS_DIR 指定）。
if [ -n "${MINI_AGENT_MODELS_DIR:-}" ]; then
  MODELS_DIR="$MINI_AGENT_MODELS_DIR"
elif [ -f "$REPO_ROOT/mini-agent-desktop/models/yuan-embedding-2.0-zh.int8.onnx" ]; then
  MODELS_DIR="$REPO_ROOT/mini-agent-desktop/models"
else
  MODELS_DIR="$HOME/.miniagent/models"
fi

if [ ! -f "$MODELS_DIR/yuan-embedding-2.0-zh.int8.onnx" ] || [ ! -f "$MODELS_DIR/tokenizer.json" ]; then
  echo "警告: $MODELS_DIR 下缺少 ONNX 模型或 tokenizer。" >&2
  echo "      应用仍会启动，但语义检索会降级为「没有向量」——症状是检索不准，不是报错。" >&2
  echo "      从构建机拷 yuan-embedding-2.0-zh.int8.onnx 与 tokenizer.json 过来，或用" >&2
  echo "      MINI_AGENT_MODELS_DIR 指向已有目录。" >&2
fi

export SPRING_PROFILES_ACTIVE=prod

export DB_URL="${DB_URL:-jdbc:mysql://127.0.0.1:3306/mini_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8}"
export DB_USERNAME="${DB_USERNAME:-root}"
export DB_PASSWORD="${DB_PASSWORD:-$MYSQL_ROOT_PASSWORD}"

# application-prod.yml 里写的是 host: redis（容器名）。jar 在宿主机跑，必须覆盖成
# 127.0.0.1，否则 DNS 解析失败、启动卡在 Redis 连接上。
export SPRING_DATA_REDIS_HOST="${SPRING_DATA_REDIS_HOST:-127.0.0.1}"
export SPRING_DATA_REDIS_PORT="${SPRING_DATA_REDIS_PORT:-6379}"

# 断言要求是精确 HTTPS origin。本机用同源页面访问时不会触发跨域，
# 所以这里填 https://localhost 只为了过断言；真上公网换成真实域名。
export ALLOWED_ORIGINS="${ALLOWED_ORIGINS:-https://localhost}"

# 账号走本地，不连云端账号服务。
# 注意：prod 档 registration-enabled=false，所以本地模式下没有注册入口，
# 首个账号必须靠 BOOTSTRAP_ADMIN_USERNAME / BOOTSTRAP_ADMIN_PASSWORD 创建。
# 不想要这套就改成指向真实的账号服务：ACCOUNT_BASE_URL=http://<host>:8081
export ACCOUNT_BASE_URL="${ACCOUNT_BASE_URL:-}"

export AGENT_MEMORY_VECTOR_BACKEND="${AGENT_MEMORY_VECTOR_BACKEND:-local}"
export AGENT_CODEBASE_EMBEDDING_PROVIDER="${AGENT_CODEBASE_EMBEDDING_PROVIDER:-local-onnx}"
export MINI_AGENT_MODELS_DIR="$MODELS_DIR"
export MINI_AGENT_HOME="${MINI_AGENT_HOME:-$HOME/.miniagent}"

# ── 3. 检查 jar 与中间件 ─────────────────────────────────────────
if [ ! -f "$JAR" ]; then
  echo "找不到 jar: $JAR" >&2
  echo "先打包：./mvnw package -pl mini-agent-app -am -DskipTests" >&2
  exit 1
fi

# 中间件直连探测：只看 127.0.0.1 上端口是否在监听，不假设任何容器运行时。
# 用 /dev/tcp 而不是 nc：目标机器不一定装了 netcat，bash 内建的能力更可靠。
# 子 shell 里 exec 打开的 fd 随子 shell 退出关闭，不会泄漏到父进程。
probe_port() {
  local port="$1" name="$2" hint="$3"
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    return 0
  fi
  echo "警告: $name 未在 127.0.0.1:$port 监听。$hint" >&2
}

probe_port 3306 mysql "prod 档在启动断言阶段就会因连不上 MySQL 而拒绝启动。"
probe_port 6379 redis "prod 档会因连不上 Redis 而启动失败。"

# ── 4. 启动 ──────────────────────────────────────────────────────
# 端口占用不要静默切端口：壳/反代/文档里写的都是 8080，悄悄换端口会让人查半天。
if command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q ":$PORT "; then
  echo "端口 $PORT 已被占用。用 --port 换一个，或先停掉占用者。" >&2
  exit 1
fi

echo "启动 MiniAgent: profile=prod port=$PORT jar=$JAR"
echo "  数据目录: $MINI_AGENT_HOME"
echo "  模型目录: $MODELS_DIR"

if [ "$DAEMON" -eq 1 ]; then
  mkdir -p "$MINI_AGENT_HOME/logs"
  nohup java -jar "$JAR" --server.port="$PORT" \
    >> "$MINI_AGENT_HOME/logs/app.log" 2>&1 &
  echo "已后台启动，pid=$!，日志: $MINI_AGENT_HOME/logs/app.log"
  echo "健康检查: curl -f http://127.0.0.1:$PORT/actuator/health"
else
  exec java -jar "$JAR" --server.port="$PORT"
fi
