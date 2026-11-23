-- 单次 LLM 调用的用量明细。
--
-- 为什么需要它：V8 的 user_daily_usage 是「用户 × 日期 × 模型」的累计，
-- 能回答「这天花了多少」，但回答不了「这次首 token 多久、耗时多久、推理强度多少、
-- 用的哪个端点」。这些是逐次调用的属性，聚合后就没了。
--
-- 与 V8 的分工：
--   user_daily_usage  → 仪表盘、配额、账单（查询轻，行数 = 用户 × 天 × 模型）
--   user_llm_call_usage → 排障与成本归因（一次调用一行，可查单次延迟）
--
-- request_id 做唯一键：同一次调用被重复上报（重试、断线重连）时只会留一行。
-- 没有它，明细会随重试线性膨胀，且延迟统计会被重试样本污染。
--
-- 为什么不存 api key 原文：这张表会被后台查询和导出，密钥不该落在这里。
-- 只存脱敏后的末四位，用于区分「哪个 key 在跑量」。
CREATE TABLE IF NOT EXISTS user_llm_call_usage (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    user_id             BIGINT       NOT NULL,
    session_id          VARCHAR(100) NOT NULL DEFAULT '',
    request_id          VARCHAR(128) NOT NULL,

    -- 调用参数快照（对应上游控制台的「模型 / 推理强度 / 端点 / 类型 / 计费模式」列）
    model               VARCHAR(64)  NOT NULL DEFAULT '',
    reasoning_effort    VARCHAR(32)  NOT NULL DEFAULT '',
    endpoint            VARCHAR(64)  NOT NULL DEFAULT '',
    streaming           TINYINT      NOT NULL DEFAULT 1,
    billing_mode        VARCHAR(16)  NOT NULL DEFAULT 'TOKEN',
    api_key_hint        VARCHAR(16)  NOT NULL DEFAULT '',

    -- token 与成本（微元整数，避免浮点累加漂移）
    input_tokens        BIGINT       NOT NULL DEFAULT 0,
    cached_input_tokens BIGINT       NOT NULL DEFAULT 0,
    output_tokens       BIGINT       NOT NULL DEFAULT 0,
    total_tokens        BIGINT       NOT NULL DEFAULT 0,
    cost_micros         BIGINT       NOT NULL DEFAULT 0,

    -- 延迟：ttft 只对流式调用有值，非流式记 0 并在 streaming 上区分
    ttft_millis         BIGINT       NOT NULL DEFAULT 0,
    duration_millis     BIGINT       NOT NULL DEFAULT 0,

    user_agent          VARCHAR(256) NOT NULL DEFAULT '',
    created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    UNIQUE KEY uk_ulcu_request (request_id)
);

-- 主查询是「某用户最近 N 天」，按时间倒序分页。
CREATE INDEX idx_ulcu_user_time ON user_llm_call_usage (user_id, created_at);
-- 排障时按会话串起一次任务的全部调用。
CREATE INDEX idx_ulcu_session ON user_llm_call_usage (session_id, created_at);
