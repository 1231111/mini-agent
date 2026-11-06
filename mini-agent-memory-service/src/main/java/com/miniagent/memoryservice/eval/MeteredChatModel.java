package com.miniagent.memoryservice.eval;

import com.miniagent.common.llm.TokenCount;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.concurrent.atomic.AtomicLong;

/** 给回放和裁判模型计 token，按作业清零后记入租户评估用量。 */
public final class MeteredChatModel implements ChatModel {
    private final ChatModel delegate;
    private final AtomicLong tokens = new AtomicLong();

    public MeteredChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        ChatResponse response = delegate.chat(request);
        tokens.addAndGet(TokenCount.of(response, String.valueOf(request.messages()).length()));
        return response;
    }

    long drain() {
        return tokens.getAndSet(0);
    }
}
