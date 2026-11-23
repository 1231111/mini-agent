-- 按「用户 × 日期 × 模型」聚合的 token 用量。
--
-- 为什么不存每次 LLM 调用的明细：
--   账号服务是跨设备可查的汇总权威，不是执行日志。一次调用一行会让这张表在长会话
--   场景下迅速膨胀（一个会话几百行），而查询诉求只有「这个人这天用了多少、花了多少」。
--   明细留在 agent 侧的 agent_llm_usage（本地库），需要逐轮排障时查那边。
--
-- 为什么 model 放进唯一键而不是另建维度表：
--   同一用户同一天可能用多个模型（主模型 + 视觉模型），成本差异大，必须能分开算。
--   模型集合随配置变化，用字符串比外键表更稳；空串表示调用方未提供模型名。
--
-- 为什么 cost_micros 是「微元」整数：
--   金额用 double 累加会出现分位漂移，账单对不上。统一用百万分之一元存整数。
--   注意：这个值由写入方按当时单价算好再传，账号服务不在这里查价——
--   调价后历史行必须保持不变，重算会把旧账单改掉。
CREATE TABLE IF NOT EXISTS user_daily_usage (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL,
    usage_day           DATE         NOT NULL,
    model               VARCHAR(64)  NOT NULL DEFAULT '',

    input_tokens        BIGINT       NOT NULL DEFAULT 0,
    cached_input_tokens BIGINT       NOT NULL DEFAULT 0,
    output_tokens       BIGINT       NOT NULL DEFAULT 0,
    total_tokens        BIGINT       NOT NULL DEFAULT 0,
    cost_micros         BIGINT       NOT NULL DEFAULT 0,
    event_count         BIGINT       NOT NULL DEFAULT 0,

    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_udu_user_day_model (user_id, usage_day, model)
);

-- 独立建索引而不是写进 CREATE TABLE：表内 KEY 语法在 H2 的 MySQL 兼容模式下不被接受，
-- 而迁移脚本一旦在哪个环境跑不通，整条链就停在那里。
CREATE INDEX idx_udu_user_day ON user_daily_usage (user_id, usage_day);
