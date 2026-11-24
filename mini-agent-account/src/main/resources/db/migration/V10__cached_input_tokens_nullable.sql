-- 缓存命中 token 改为可空，用来区分「未采集」和「确认零命中」。
--
-- 背景：langchain4j 的 OpenAiTokenUsage.inputTokensDetails().cachedTokens() 返回 Integer，
-- 上游没返回 details 时是 null。把它写成 0 会让下游误判为「缓存零命中」，
-- 进而按全价计费 —— 偏差 2~3 倍，而且完全静默（请求成功、日志无异常）。
--
-- 语义约定（与 LangChain UsageMetadata、火山引擎流式文档一致）：
--   NULL  = 上游未返回，未采集
--   0     = 确认零命中
--   N > 0 = 命中 N 个 token
--
-- 不改成「再加一个 known 布尔列」：两列表达一个状态，必然出现 (0, true) 与 (0, false)
-- 这种要靠约定维持的组合，NULL 一个字段就把状态空间收干净了。
ALTER TABLE user_llm_call_usage
    MODIFY COLUMN cached_input_tokens BIGINT NULL;
