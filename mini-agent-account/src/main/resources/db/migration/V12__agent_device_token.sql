-- 客户机服务令牌：每个用户一把，登录时轮换。
--
-- 背景：账号服务不签发也不校验用户的 JWT（JWT 由客户机后端签），所以它无法自己判断
-- 请求里的 userId 是否可信。原来靠一把全局共享的 internal-key 担保，但那把 key
-- 会随客户端分发到用户机器上，谁提取出来都能写任意 userId 的用量。
--
-- 改成每用户一把之后：
--   1. 冒用别人的 userId 会被拦（令牌里的 userId 与请求里的必须一致）
--   2. 撤销量级降到单个用户（重新登录即轮换）
--   3. 令牌带签发时间，能回答"这个凭证是谁、什么时候拿到的"
--
-- 只存 SHA-256：库泄露也拿不到可用于调用的原文，与 memory_refresh_token_hash 同一处理方式。
-- 单设备语义：同一用户同时只有一把，重新登录会把上一次作废。
ALTER TABLE users
    ADD COLUMN agent_token_hash       CHAR(64)    NULL,
    ADD COLUMN agent_token_issued_at  DATETIME(6) NULL,
    ADD COLUMN agent_token_expires_at DATETIME(6) NULL;

-- 校验路径是「拿令牌算哈希 → 反查用户」，必须走索引；唯一约束顺带保证一 hash 一用户。
CREATE UNIQUE INDEX uk_users_agent_token_hash ON users (agent_token_hash);
