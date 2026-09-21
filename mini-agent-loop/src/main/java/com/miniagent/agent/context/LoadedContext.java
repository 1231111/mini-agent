package com.miniagent.agent.context;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.Map;

/**
 * {@link ContextLoader#load} 的返回值：本轮要喂给模型的系统提示 + 对话历史 + 加载说明。
 *
 * @param scopeKey 本轮所属的<b>任务作用域键</b>（{@code sessionId} 或 {@code sessionId#n}）。
 *                 任务级状态（规划图、压缩摘要）都按它取，因此本轮所有下游读到的是同一个任务。
 *                 与 {@code sessionId} 的区别：同一个会话里换任务后 sessionId 不变、scopeKey 会变。
 */
public record LoadedContext(
        String systemPrompt,
        List<ChatMessage> history,
        ContextLoadPolicy policy,
        String scopeKey,
        Map<String, Object> loadInfo
) {
    public LoadedContext {
        history = history == null ? List.of() : List.copyOf(history);
        loadInfo = loadInfo == null ? Map.of() : Map.copyOf(loadInfo);
    }
}
