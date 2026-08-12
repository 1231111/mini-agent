package com.miniagent.agent.context;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.Map;

/**
 * {@link ContextLoader#load} 的返回值：本轮要喂给模型的系统提示 + 对话历史 + 加载说明。
 */
public record LoadedContext(
        String systemPrompt,
        List<ChatMessage> history,
        ContextIntentPolicy policy,
        Map<String, Object> loadInfo
) {
    public LoadedContext {
        history = history == null ? List.of() : List.copyOf(history);
        loadInfo = loadInfo == null ? Map.of() : Map.copyOf(loadInfo);
    }
}
