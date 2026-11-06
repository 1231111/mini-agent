package com.miniagent.agent.context;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.Map;

/**
 * {@link ContextLoader#load} 的返回值：本轮要喂给模型的系统提示 + 对话历史 + 加载说明。
 *
 * @param scopeKey 本轮所属的任务作用域键（{@code sessionId} 或 {@code sessionId#n}）。
 * @param taskId 该作用域对应的会话内任务序号，供事件和执行线程关联使用。
 */
public record LoadedContext(
        String systemPrompt,
        List<ChatMessage> history,
        ContextLoadPolicy policy,
        String scopeKey,
        long taskId,
        Map<String, Object> loadInfo
) {
    public LoadedContext {
        history = history == null ? List.of() : List.copyOf(history);
        loadInfo = loadInfo == null ? Map.of() : Map.copyOf(loadInfo);
    }
}
