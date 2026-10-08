-- 云端账号体系：把本地 users 行变成「云端身份在本机的影子记录」。
--
-- 背景：账号注册与登录校验都改由云端账号服务（agent.auth.cloud.base-url）承担，
-- 但本地鉴权链一个字都不能少：
--   1. SignedSessionFilter.resolvePrincipal() 每个请求都要 userRepository.findById(userId)，
--      本地没有这一行 → 直接 401，token 本身再合法也没用；
--   2. chat_conversations.user_id / agent_memory_entries.user_id 等业务外键需要落点。
-- 所以云端用户在本地必须有对应行，external_id 就是「这一行对应云端哪个人」的映射键。
--
-- 为什么唯一索引可以直接建、不会打挂存量数据：
--   external_id 为 NULL 表示「纯本地账号」，而 NULL 在唯一索引里互不相等。
--   H2(MODE=MySQL) 与 MySQL 都是这个语义 —— 已在 H2 上实测（同表连续插入两行 NULL 不报错）。
--   所以现存的本地账号不会被这条索引影响。
--
-- 长度 128 刻意放宽：云端 id 可能是 UUID / 雪花号 / 带前缀的字符串，
-- 这里不对它的形状做任何假设。

ALTER TABLE users ADD COLUMN external_id VARCHAR(128);

CREATE UNIQUE INDEX ux_users_external_id ON users (external_id);
