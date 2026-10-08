package com.miniagent.agent.core;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;

/**
 * Token 用量追踪器：按会话统计输入/输出 token 和工具调用次数。
 * 对标 hermes-agent 的 usage tracking。
 */
@Component
public class TokenUsageTracker {

    public static class UsageStats {
        public long inputTokens = 0;
        public long outputTokens = 0;
        public int toolCalls = 0;
        public int llmCalls = 0;
        public long lastUpdated = System.currentTimeMillis();

        public void addInput(long tokens) { inputTokens += tokens; lastUpdated = System.currentTimeMillis(); }
        public void addOutput(long tokens) { outputTokens += tokens; lastUpdated = System.currentTimeMillis(); }
        public void addToolCall() { toolCalls++; lastUpdated = System.currentTimeMillis(); }
        public void addLlmCall() { llmCalls++; lastUpdated = System.currentTimeMillis(); }

        public long totalTokens() { return inputTokens + outputTokens; }

        /** 估算费用（MiMo 免费，但保留接口供其他模型使用） */
        public double estimateCost(double inputPricePer1k, double outputPricePer1k) {
            return inputTokens / 1000.0 * inputPricePer1k + outputTokens / 1000.0 * outputPricePer1k;
        }

        @Override
        public String toString() {
            return String.format("input=%d, output=%d, total=%d, llmCalls=%d, toolCalls=%d",
                    inputTokens, outputTokens, totalTokens(), llmCalls, toolCalls);
        }
    }

    /** 一次模型调用记完后的回调。实现方自己决定要不要异步上报。 */
    @FunctionalInterface
    public interface LlmCallListener {
        void onLlmCall(String sessionId, long inputTokens, long outputTokens);
    }

    private static final Map<String, UsageStats> stats = new ConcurrentHashMap<>();
    private static volatile LlmCallListener listener;

    public static void setListener(LlmCallListener next) {
        listener = next;
    }

    public static void add(String sessionId, long inputTokens, long outputTokens, int toolCalls) {
        if (Objects.isNull(sessionId)) {
            return;
        }
        UsageStats s = stats.computeIfAbsent(sessionId, k -> new UsageStats());
        if (inputTokens > 0) {
            s.addInput(inputTokens);
        }
        if (outputTokens > 0) {
            s.addOutput(outputTokens);
        }
        for (int i = 0; i < toolCalls; i++) {
            s.addToolCall();
        }
        s.addLlmCall();
        LlmCallListener current = listener;
        if (current == null) {
            return;
        }
        try {
            current.onLlmCall(sessionId, inputTokens, outputTokens);
        } catch (RuntimeException ignored) {
            // 上报失败不能打断模型循环。
        }
    }

    public static void addToolCall(String sessionId) {
        if (Objects.isNull(sessionId)) {
            return;
        }
        stats.computeIfAbsent(sessionId, k -> new UsageStats()).addToolCall();
    }

    public static UsageStats get(String sessionId) {
        return stats.getOrDefault(sessionId, new UsageStats());
    }

    public static Map<String, UsageStats> getAll() {
        return Map.copyOf(stats);
    }

    public static void clear(String sessionId) {
        stats.remove(sessionId);
    }
}
