-- 会员：套餐定义、订阅、订单。
--
-- 这三张表只属于账号服务，云端 agent 不映射它们 —— 那边只读 tenants.daily_token_limit，
-- 也就是"会员等级被翻译之后"的那个数。会员的语义完整地留在这个服务里。

CREATE TABLE IF NOT EXISTS membership_plans (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(32) NOT NULL,
    name VARCHAR(64) NOT NULL,
    daily_token_limit BIGINT NOT NULL DEFAULT 0,
    max_concurrent_tasks INT NOT NULL DEFAULT 2,
    price_cents BIGINT NOT NULL DEFAULT 0,
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    duration_days INT NOT NULL DEFAULT 30,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_membership_plans_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 刻意不建到 users / tenants 的外键：
--   1. 这两张表的所有者是 agent 侧，跨服务的写顺序依赖会让"先建账号还是先建订阅"
--      变成一个需要靠部署顺序保证的事；
--   2. 订单与订阅是财务记录，不该因为某个用户行被删而级联消失。
-- 完整性由 service 层的显式校验承担（见 MembershipService.createOrder / markPaid）。

CREATE TABLE IF NOT EXISTS membership_subscriptions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    tenant_id BIGINT NOT NULL,
    plan_code VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    expire_at DATETIME(6) NULL,
    source_order_no VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_ms_user_status (user_id, status),
    KEY idx_ms_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS membership_orders (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    plan_code VARCHAR(32) NOT NULL,
    amount_cents BIGINT NOT NULL,
    currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
    status VARCHAR(16) NOT NULL,
    channel VARCHAR(32) NULL,
    channel_txn_id VARCHAR(128) NULL,
    paid_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    -- 支付回调靠 order_no 定位订单，唯一性是幂等的前提：
    -- 单号若可重复，"找到哪一单"就没有确定答案。
    UNIQUE KEY uk_membership_orders_order_no (order_no),
    KEY idx_mo_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ═══ 种子数据 ═══
--
-- free 是系统保留套餐：注册流程依赖它来确定新用户的默认额度
-- （见 MembershipService.startFreeSubscription 与 AccountAuthService.createPersonalTenant）。
-- 缺失会让注册直接失败，这是刻意的 —— 比静默给一个"看起来合理"的默认额度好，
-- 后者会变成一条没人记得的隐性承诺，改套餐时也不会有人想到去改它。
--
-- daily_token_limit 的单位与 tenants.daily_token_limit 完全一致，订阅生效时直接抄过去。
-- 200000 是保守取值：新用户默认不应该拿到接近无限量的额度。
--
-- duration_days = 0 表示永久（free 档），代码里据此把 expire_at 置为 NULL。
--
-- 用 INSERT ... SELECT ... WHERE NOT EXISTS 而不用裸 INSERT：
-- 多实例同时启动时两个进程可能都执行到这个脚本，裸 INSERT 会撞唯一键而失败。

INSERT INTO membership_plans
    (code, name, daily_token_limit, max_concurrent_tasks, price_cents, currency, duration_days, enabled)
SELECT 'free', '免费版', 200000, 2, 0, 'CNY', 0, TRUE
WHERE NOT EXISTS (SELECT 1 FROM membership_plans WHERE code = 'free');

INSERT INTO membership_plans
    (code, name, daily_token_limit, max_concurrent_tasks, price_cents, currency, duration_days, enabled)
SELECT 'pro', '专业版', 5000000, 5, 3900, 'CNY', 30, TRUE
WHERE NOT EXISTS (SELECT 1 FROM membership_plans WHERE code = 'pro');
