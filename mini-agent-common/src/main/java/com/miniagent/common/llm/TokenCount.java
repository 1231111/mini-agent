package com.miniagent.common.llm;

import dev.langchain4j.model.chat.response.ChatResponse;

/** 一次模型调用的 token 数：优先取供应商回报的用量，没有时按字符估算。 */
public final class TokenCount {
    /** 中文约 1–2 字符 / token，取保守值，宁可多计不少计。 */
    private static final double CHARS_PER_TOKEN_ESTIMATE = 2;

    private TokenCount() {
    }

    /** @param promptChars 本次请求所有消息的字符数（用于无用量回报时估算） */
    public static long of(ChatResponse response, long promptChars) {
        if (response.tokenUsage() != null && response.tokenUsage().totalTokenCount() != null) {
            return response.tokenUsage().totalTokenCount();
        }
        String out = response.aiMessage() == null ? null : response.aiMessage().text();
        long chars = promptChars + (out == null ? 0 : out.length());
        return (long) Math.ceil(chars / CHARS_PER_TOKEN_ESTIMATE);
    }
}
