-- 用量明细的补充维度。
--
-- 四列都是「能拿到但之前没地方存」或「查询需要但没有」的字段，全部带默认值，
-- 不需要数据回填 —— 存量行会得到 0 / 空串，语义是「历史未采集」，不是「当时为零」。
--
-- reasoning_tokens：思考模型的思考 token 数。它是 output 的子集，单独存才能回答
--   「这次思考花了多少」。数据源 OpenAiTokenUsage.outputTokensDetails().reasoningTokens()，
--   和 cached_tokens 一样已经由 langchain4j 解析好，不存就是白丢。
-- provider：厂商名（deepseek / openai / anthropic）。没有它只能靠模型名猜厂商，
--   换模型名（deepseek-chat → deepseek-v3.2）统计就断了。
-- execution_id：一次任务执行的 id。一个会话可能跑多次任务，只按 session_id 分组
--   会把不同任务混在一起，回答不了「这次任务烧了多少」。
-- cached_unknown_calls：聚合表专用。表示「这段时间有多少次调用没采集到缓存明细」。
--   单靠 cached_input_tokens = 0 无法区分「零命中」和「没采集」，
--   而聚合是混着算的，NULL 也表达不了「100 次里 30 次未知」。
--
-- 合并成一条 ALTER：MySQL 对同一张表的多列变更只重建一次表结构。
ALTER TABLE user_llm_call_usage
    ADD COLUMN reasoning_tokens      BIGINT      NOT NULL DEFAULT 0,
    ADD COLUMN provider              VARCHAR(32) NOT NULL DEFAULT '',
    ADD COLUMN execution_id          VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE user_daily_usage
    ADD COLUMN cached_unknown_calls  BIGINT      NOT NULL DEFAULT 0;

-- 明细表的清理按时间删：现有 idx_ulcu_user_time 最左前缀是 user_id，
-- 「WHERE created_at < ?」用不上它，会退化成全表扫描。
CREATE INDEX idx_ulcu_created ON user_llm_call_usage (created_at);
