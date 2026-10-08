-- Preserve task history for audit and recovery while hiding it from active APIs.
--
-- ═══ 为什么不是一句裸的 ALTER TABLE ═══
--
-- 原本这里是：
--     ALTER TABLE chat_tasks
--         ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE,
--         ADD KEY idx_chat_task_user_deleted_session_created (...);
--
-- 它在干净库上没问题，但在本仓库的库上必然失败。原因是默认档的配置组合：
--     application.yml:  spring.flyway.enabled=false  +  spring.jpa.hibernate.ddl-auto=update
-- 只要有人用默认档连过这个库（开发日常就会），Hibernate 就会照实体定义把
-- chat_tasks.deleted 直接建出来 —— 而 Flyway 的历史表还停在 V4。
-- 于是 prod 档（ddl-auto=validate + flyway.enabled=true）上来时，V5 撞
-- "Duplicate column name 'deleted'"（SQLState 42S21 / errno 1060）。
--
-- 更麻烦的是 MySQL 的 DDL 不在事务里：失败会在 flyway_schema_history 里留下一条
-- success=0 的记录，此后每次启动 Flyway 都直接拒绝（"Detected failed migration"），
-- 必须先 repair 掉那条记录才能继续 —— 一次失败换来的是"迁移链整体卡死"。
--
-- 另外注意"列已存在"并不等于"V5 的意图已满足"：Hibernate 只建列、不建这个复合索引，
-- 所以漂移库里索引一定缺失。列和索引必须各守一次，不能只守列就收工。
--
-- ═══ 关于修改已存在的迁移文件 ═══
--
-- 改已执行过的迁移会 checksum 漂移、应用起不来。这里可以改，是因为 V5 从未在任何库上
-- 成功执行过（本库历史表只有 V1..V4，且 V5 那条是 success=0 的失败记录）。
-- 从"迁移文件一旦发布即冻结"的角度看，这是最后一次能改的时机。
--
-- ═══ 为什么用 information_schema + PREPARE，而不是 IF NOT EXISTS ═══
--
-- MySQL 8 **不支持** ALTER TABLE ... ADD COLUMN IF NOT EXISTS（那是 MariaDB 的扩展），
-- 实测报 ERROR 1064。所以只能用 information_schema 先查再拼动态 SQL。
-- 下面的写法在两种库上都实测过：
--   列存在 + 无索引 → 跳过列、建索引
--   列不存在        → 建列（tinyint(1) NOT NULL DEFAULT 0）
--   连跑两次        → 第二次两个守卫都跳过，无报错（幂等）

-- 列：仅在缺失时添加。
SET @has_deleted_col := (SELECT COUNT(*)
                         FROM information_schema.COLUMNS
                         WHERE TABLE_SCHEMA = DATABASE()
                           AND TABLE_NAME = 'chat_tasks'
                           AND COLUMN_NAME = 'deleted');
SET @ddl := IF(@has_deleted_col = 0,
    'ALTER TABLE chat_tasks ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 索引：仅在缺失时创建。复合索引的列顺序 (user_id, deleted, session_id, created_at)
-- 是为「某用户未删除的会话列表按时间倒序」这一条查询写的，顺序不能调换。
SET @has_deleted_idx := (SELECT COUNT(*)
                         FROM information_schema.STATISTICS
                         WHERE TABLE_SCHEMA = DATABASE()
                           AND TABLE_NAME = 'chat_tasks'
                           AND INDEX_NAME = 'idx_chat_task_user_deleted_session_created');
SET @ddl := IF(@has_deleted_idx = 0,
    'ALTER TABLE chat_tasks ADD KEY idx_chat_task_user_deleted_session_created (user_id, deleted, session_id, created_at)',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
