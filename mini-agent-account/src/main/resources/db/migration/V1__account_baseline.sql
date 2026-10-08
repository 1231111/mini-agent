-- 账号服务的 schema 基线：tenants 与 users。
--
-- ═══ 为什么是 IF NOT EXISTS ═══
--
-- 本服务与云端 agent 共用同一个 MySQL 库，而这两张表在 agent 的 V1/V2/V8 里也建过一次
-- （客户机的 H2 库只有 agent 一个应用，不受影响）。谁先启动谁建，后者必须是 no-op。
--
-- 部署顺序（docker-compose 用 depends_on 表达）：agent 先，account 后。
--   agent   跑 V1..V8 → 建出 tenants / users / 以及其他全部业务表
--   account 跑本脚本   → CREATE TABLE IF NOT EXISTS 全部跳过，只建会员三表
--
-- 反过来（account 先）会让 agent 的 V2 撞上 "table already exists" 而启动失败。
-- 那条 CREATE TABLE 是历史迁移，不能改成 IF NOT EXISTS —— 会改变已执行库的 checksum，
-- Flyway 会直接报校验失败。所以顺序是有要求的，不是哪个先都行。
--
-- ═══ 列定义必须与 agent 侧一致 ═══
--
-- 这里写的是 V1 + V2 + V8 叠加后的最终形状。两个服务各自 ddl-auto=validate，
-- 但 validate 只能校验"我的实体对得上我的表"，校验不了"两个服务对同一列的理解是否相同"。
-- 改这里之前请对照 agent 侧的迁移脚本。
--
-- external_id 在本服务的实体里<b>不映射</b>（那是客户机侧的映射键，账号服务里
-- users.id 本身就是云端身份），但表里必须有这一列 —— 它由 agent 的 V8 建，
-- 这里的定义是给"account 单独起在一个空库上"的情形兜底。

CREATE TABLE IF NOT EXISTS tenants (
    id BIGINT NOT NULL AUTO_INCREMENT,
    slug VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    daily_token_limit BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenants_slug (slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(50) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100),
    tenant_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    external_id VARCHAR(128),
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY ux_users_external_id (external_id),
    KEY idx_users_tenant (tenant_id),
    CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
