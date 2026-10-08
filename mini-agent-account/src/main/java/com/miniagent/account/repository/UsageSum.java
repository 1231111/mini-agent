package com.miniagent.account.repository;

/** 一段时间里的 token 合计和 LLM 请求次数。次数是行数。 */
public record UsageSum(long inputTokens, long outputTokens, long llmCalls) {
}
