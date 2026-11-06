#!/usr/bin/env bash
#
# 部署前补齐服务间密钥：缺失才生成，已有则原样保留。
#
#   bash scripts/ensure-deploy-secrets.sh            # 写入仓库根目录 .env（已在 .gitignore 里）
#   bash scripts/ensure-deploy-secrets.sh /opt/app/.env
#
# 为什么要有这个脚本，而不是把密钥写进 application.yml：
#
#   这个仓库是公开的。AES-256 密钥一旦进了 git 历史，任何能读仓库的人都能自己伪造
#   ma1. 令牌，把 tenantId 填成别人的，再直接调 memory-service 的 /internal/** 接口
#   读写别人的记忆 —— 因为那边解密的成功性本身就是信任依据。所以密钥只能存在于
#   部署机的 .env 里，而 .env 不进版本库。
#
# 为什么"缺失才生成"而不是每次重建：
#
#   换掉 ACCOUNT_MEMORY_TOKEN_KEY 会让已经签发出去的记忆令牌全部作废（最长 72 小时
#   陆续过期，见 MemoryAccessToken.MAX_TTL）。部署脚本每次跑都换密钥，等于每次部署
#   都把在线用户踢下线一次。所以这里做成幂等的。
#
# 退出码：0 表示两个变量都已就绪；非 0 表示环境缺 openssl 或写不进去。

set -euo pipefail

ENV_FILE="${1:-.env}"

if ! command -v openssl >/dev/null 2>&1; then
    echo "缺少 openssl：无法生成 32 字节随机密钥。" >&2
    exit 1
fi

touch "$ENV_FILE"

# 幂等写入：键存在且非空就跳过。
# base64 输出可能含 / + =，所以 sed 用 | 作分隔符；它不含 &，替换串不会被展开。
ensure_key() {
    local key="$1"
    local current
    current="$(grep -E "^${key}=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- || true)"
    if [ -n "${current}" ]; then
        echo "保留已有的 ${key}（长度 ${#current}）"
        return 0
    fi
    local value
    value="$(openssl rand -base64 32)"
    if grep -qE "^${key}=" "$ENV_FILE"; then
        sed -i "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
    else
        printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
    fi
    echo "已生成 ${key}（Base64 解码后 32 字节）"
}

ensure_key ACCOUNT_MEMORY_TOKEN_KEY
ensure_key ACCOUNT_INTERNAL_KEY

echo "完成：${ENV_FILE} 已具备 ACCOUNT_MEMORY_TOKEN_KEY 与 ACCOUNT_INTERNAL_KEY"
