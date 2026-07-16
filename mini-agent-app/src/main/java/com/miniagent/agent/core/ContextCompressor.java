package com.miniagent.agent.core;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上下文压缩器（对标 hermes-agent context_compressor.py 5 阶段流水线）。
 *
 * 设计要点：
 *   - 5 阶段流水线：pruneOldToolResults → findTailCutIndex → generateSummary → rebuild → sanitizeOrphanedToolPairs
 *   - 按 session 隔离状态，支持多用户并发
 *   - 压缩前自动提取关键信息到中期记忆，防止信息丢失
 */
@Slf4j
@Component
public class ContextCompressor {

    @Autowired
    private  ChatModel chatModel;

    @Autowired
    private TokenEstimator tokenEstimator;
    @Autowired
    private com.miniagent.agent.memory.MemoryStore memoryStore;

    // ─── 阈值配置 ───
    /** 压缩触发阈值：上下文占比达到该比例即压缩，留出输出余量。可配置，默认 0.75。 */
    @org.springframework.beans.factory.annotation.Value("${agent.context.compress-threshold:0.75}")
    private double compressionThreshold;
    private static final double TAIL_TOKEN_RATIO = 0.4;
    private static final int PROTECT_FIRST_N = 2;
    private static final int MIN_MESSAGES_TO_COMPRESS = 8;
    private static final int DEFAULT_MAX_CONTEXT_TOKENS = 256000;
    private static final int PROTECT_TOOL_RESULTS_TAIL = 6;
    private static final int MAX_INEFFECTIVE_COMPRESSIONS = 2;
    private static final int MAX_SUMMARY_TOKENS = 3000;

    // ─── 按会话隔离的状态 ───
    private static class SessionState {
        String previousSummary;
        int ineffectiveCount = 0;
        /** 冷却计数器：压缩效果不佳时跳过后续 N 次压缩 */
        int cooldownRemaining = 0;
        /** 上次压缩是否增加了 token（负面效果） */
        boolean lastCompressionIncreasedTokens = false;
    }
    private final Map<String, SessionState> sessionStates = new ConcurrentHashMap<>();

    // 当前会话ID（由 AgentChatApplicationService 在每轮对话开始时设置）
    private static final ThreadLocal<String> currentSessionId = new ThreadLocal<>();

    /** 设置当前会话上下文 */
    public void setCurrentSession(String sessionId) {
        currentSessionId.set(sessionId);
    }

    /** 清理会话状态 */
    public void clearSession(String sessionId) {
        sessionStates.remove(sessionId);
    }

    private SessionState getState(String sessionId) {
        return sessionStates.computeIfAbsent(sessionId, k -> new SessionState());
    }

    /** 重置会话状态（用于测试） */
    public void resetSession(String sessionId) {
        sessionStates.remove(sessionId);
    }

    // ─── 阶段 1: 裁剪旧工具结果 ───
    private List<ChatMessage> pruneOldToolResults(List<ChatMessage> messages) {
        // 保留最后 PROTECT_TOOL_RESULTS_TAIL 个工具结果，把更早的工具结果替换为占位符。
        // 占位符包含工具调用摘要（如文件路径），防止模型压缩后重复读取相同文件。
        List<ChatMessage> result = new ArrayList<>();
        int toolResultCount = 0;
        for (ChatMessage msg : messages) {
            if (msg instanceof ToolExecutionResultMessage) {
                toolResultCount++;
            }
        }
        int skipCount = Math.max(0, toolResultCount - PROTECT_TOOL_RESULTS_TAIL);
        int skipped = 0;

        // 预扫描：建立 toolCallId -> 工具调用摘要 的映射
        Map<String, String> toolCallSummaries = new java.util.HashMap<>();
        for (ChatMessage msg : messages) {
            if (msg instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                for (var tc : ai.toolExecutionRequests()) {
                    toolCallSummaries.put(tc.id(), summarizeToolCall(tc.name(), tc.arguments()));
                }
            }
        }

        for (ChatMessage msg : messages) {
            if (msg instanceof ToolExecutionResultMessage tr && skipped < skipCount) {
                skipped++;
                String summary = toolCallSummaries.getOrDefault(tr.id(), tr.toolName());
                result.add(ToolExecutionResultMessage.from(tr.id(), tr.toolName(),
                        "[已裁剪] " + summary));
            } else {
                result.add(msg);
            }
        }
        if (skipped > 0) {
            log.info("预裁剪: 替换了 {} 个旧工具结果", skipped);
        }
        return result;
    }

    /** 从工具调用参数中提取关键信息摘要（如文件路径、搜索关键词） */
    private static String summarizeToolCall(String toolName, String arguments) {
        if (arguments == null || arguments.isBlank()) return toolName;
        return switch (toolName) {
            case "read_file", "write_file", "list_files" -> toolName + "(" + extractJsonField(arguments, "path") + ")";
            case "web_search" -> toolName + "(" + extractJsonField(arguments, "query") + ")";
            case "web_extract", "http_get" -> toolName + "(" + extractJsonField(arguments, "url") + ")";
            case "exec_command" -> toolName + "(" + truncateStr(extractJsonField(arguments, "command"), 60) + ")";
            case "image_generate" -> toolName + "(" + truncateStr(extractJsonField(arguments, "prompt"), 40) + ")";
            case "browser_navigate" -> toolName + "(" + extractJsonField(arguments, "url") + ")";
            default -> toolName;
        };
    }

    /** 委托给 AgentLoop 的同名方法，避免重复实现 */
    private static String extractJsonField(String json, String field) {
        return AgentLoop.extractJsonField(json, field);
    }

    private static String truncateStr(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ─── 阶段 2: 确定切割点 ───
    private int findTailCutIndex(List<ChatMessage> messages, int headEnd, int maxTokens) {
        int tailBudget = (int) (maxTokens * TAIL_TOKEN_RATIO);
        int used = 0;
        int cutIdx = messages.size();
        for (int i = messages.size() - 1; i >= headEnd; i--) {
            int cost = tokenEstimator.estimate(messages.get(i));
            if (used + cost > tailBudget) break;
            used += cost;
            cutIdx = i;
        }
        // 确保尾部包含完整的用户消息
        cutIdx = ensureLastUserInTail(messages, cutIdx, headEnd);
        // 确保不拆分 tool_call / tool_result 配对
        cutIdx = ensureToolPairIntegrity(messages, cutIdx, headEnd);
        return cutIdx;
    }

    /**
     * 确保切割点不落在 AiMessage(tool_calls) 与其对应 ToolExecutionResultMessages 之间。
     * 如果 cutIdx 处是 ToolExecutionResultMessage 且其配对的 AiMessage 在 cutIdx 之前（会被摘要掉），
     * 则前移切割点到那个 AiMessage 的位置，把整组保留在 tail 中。
     */
    private int ensureToolPairIntegrity(List<ChatMessage> messages, int cutIdx, int headEnd) {
        if (cutIdx >= messages.size()) return cutIdx;

        // 向前查找：如果 cutIdx 处是 ToolExecutionResultMessage，找到它配对的 AiMessage
        if (messages.get(cutIdx) instanceof ToolExecutionResultMessage tr) {
            String resultId = tr.id();
            for (int i = cutIdx - 1; i >= headEnd; i--) {
                if (messages.get(i) instanceof AiMessage am && am.hasToolExecutionRequests()) {
                    boolean matches = am.toolExecutionRequests().stream()
                            .anyMatch(req -> req.id().equals(resultId));
                    if (matches) {
                        return i; // 把 AiMessage 也包含在 tail 中
                    }
                }
            }
        }

        // 反向检查：如果 cutIdx-1 处是 AiMessage(tool_calls)，其 results 在 tail 中，
        // 则把 AiMessage 也放入 tail
        if (cutIdx > headEnd && messages.get(cutIdx - 1) instanceof AiMessage am
                && am.hasToolExecutionRequests()) {
            return cutIdx - 1;
        }

        return cutIdx;
    }

    /** 确保尾部以 UserMessage 开始 */
    private int ensureLastUserInTail(List<ChatMessage> messages, int cutIdx, int headEnd) {
        for (int i = cutIdx; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage) {
                return i;
            }
        }
        return cutIdx;
    }

    // ─── 序列化 ───
    private String serializeForSummary(List<ChatMessage> msgs) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : msgs) {
            if (msg instanceof UserMessage um) {
                sb.append("用户: ").append(extractText(um)).append("\n");
            } else if (msg instanceof AiMessage am) {
                sb.append("助手: ").append(am.text() != null ? am.text() : "[工具调用]").append("\n");
            } else if (msg instanceof ToolExecutionResultMessage tr) {
                String t = tr.text() == null ? "" : tr.text();
                sb.append("工具结果(").append(tr.toolName()).append("): ")
                  .append(t.substring(0, Math.min(t.length(), 200))).append("\n");
            } else if (msg instanceof SystemMessage sm) {
                String t = sm.text() == null ? "" : sm.text();
                sb.append("系统: ").append(t.substring(0, Math.min(t.length(), 200))).append("\n");
            }
        }
        return sb.toString();
    }

    // ─── 摘要 + 记忆提取（合并为单次 LLM 调用）───
    private static final String SUMMARY_PREAMBLE = "你是一个上下文压缩专家。你的任务是生成简洁的交接摘要。\n" +
            "保留：关键决策、待办事项、重要数据、用户明确要求记住的内容。\n" +
            "删除：闲聊、重复信息、已完成的工具调用细节。";

    private static final String SUMMARY_TEMPLATE = "输出 %d 字以内的摘要，使用简洁的要点列表格式。";

    private static final String MEMORY_SEPARATOR = "---MEMORY---";

    /**
     * 合并摘要生成 + 记忆提取为单次 LLM 调用，节省一半压缩延迟。
     * 输出格式：摘要部分 + "---MEMORY---" 分隔符 + 记忆提取部分。
     */
    private String generateSummaryAndExtractMemory(List<ChatMessage> toSummarize, int summaryBudget, SessionState state) {
        String content = serializeForSummary(toSummarize);
        String memoryPrompt = "\n\n在摘要之后，另起一行输出 " + MEMORY_SEPARATOR + "，然后提取关键信息到长期记忆（只提取将来仍有用的）：\n" +
                "提取类别：1)用户偏好 2)重要决策 3)技术上下文 4)行动项\n" +
                "格式：每条一行 \"类别: 内容\"。如果没有值得记忆的内容，在分隔符后输出 \"无\"。";

        String prompt;
        if (state.previousSummary != null) {
            prompt = SUMMARY_PREAMBLE + "\n" +
                "你正在更新一个上下文压缩摘要。以下是上一次的摘要和新的对话轮次。\n\n" +
                "上一次摘要：\n" + state.previousSummary + "\n\n" +
                "新的对话轮次：\n" + content + "\n\n" +
                "更新摘要，保留仍然相关的信息，添加新的操作和状态变化。\n" +
                String.format(SUMMARY_TEMPLATE, summaryBudget) + memoryPrompt;
        } else {
            prompt = SUMMARY_PREAMBLE + "\n" +
                "为以下对话生成结构化交接摘要：\n\n" + content + "\n\n" +
                String.format(SUMMARY_TEMPLATE, summaryBudget) + memoryPrompt;
        }

        try {
            List<ChatMessage> req = List.of(new UserMessage(prompt));
            ChatResponse resp = chatModel.chat(ChatRequest.builder().messages(req).build());
            String fullResponse = resp.aiMessage().text();
            if (fullResponse == null) return null;

            // 按分隔符拆分：摘要 + 记忆
            String summary;
            String memoryPart = null;
            int sepIdx = fullResponse.indexOf(MEMORY_SEPARATOR);
            if (sepIdx >= 0) {
                summary = fullResponse.substring(0, sepIdx).strip();
                memoryPart = fullResponse.substring(sepIdx + MEMORY_SEPARATOR.length()).strip();
            } else {
                summary = fullResponse.strip();
            }

            if (summary != null) state.previousSummary = summary;

            // 异步保存记忆提取结果
            if (memoryPart != null && !memoryPart.isBlank() && !memoryPart.contains("无")) {
                try {
                    String existing = memoryStore.getRawMidtermMemory();
                    String newMemory = existing.isEmpty() ? memoryPart : existing + "\n" + memoryPart;
                    if (newMemory.length() > 3000) {
                        newMemory = newMemory.substring(newMemory.length() - 3000);
                    }
                    memoryStore.updateMidtermMemory(newMemory);
                    log.info("压缩时提取记忆成功，提取 {} 字", memoryPart.length());
                } catch (Exception e) {
                    log.warn("压缩时保存记忆失败（不影响压缩）: {}", e.getMessage());
                }
            }

            return summary;
        } catch (Exception e) {
            log.error("摘要生成失败: {}", e.getMessage());
            return null;
        }
    }

    // ─── 阶段 5: 清理孤立配对 ───
    private List<ChatMessage> sanitizeOrphanedToolPairs(List<ChatMessage> messages) {
        Set<String> callIds = new HashSet<>();
        for (ChatMessage m : messages) {
            if (m instanceof AiMessage am && am.hasToolExecutionRequests()) {
                for (var req : am.toolExecutionRequests()) callIds.add(req.id());
            }
        }
        List<ChatMessage> cleaned = new ArrayList<>();
        int removed = 0;
        for (ChatMessage m : messages) {
            if (m instanceof ToolExecutionResultMessage tr && !callIds.contains(tr.id())) {
                removed++; continue;
            }
            cleaned.add(m);
        }
        if (removed > 0) log.info("清理了 {} 个孤立 tool_result", removed);
        return cleaned;
    }

    // ─── 主入口 ───
    public List<ChatMessage> maybeCompress(List<ChatMessage> messages, int maxContextTokens) {
        return maybeCompress(messages, maxContextTokens, null);
    }

    public List<ChatMessage> maybeCompress(List<ChatMessage> messages, int maxContextTokens, String sessionId) {
        if (messages == null || messages.size() < MIN_MESSAGES_TO_COMPRESS) return messages;

        int maxTokens = maxContextTokens > 0 ? maxContextTokens : DEFAULT_MAX_CONTEXT_TOKENS;
        double ratio = tokenEstimator.contextRatio(messages, maxTokens);

        if (ratio < compressionThreshold) return messages;

        String effectiveSessionId = sessionId != null ? sessionId : currentSessionId.get();
        SessionState state = effectiveSessionId != null ? getState(effectiveSessionId) : new SessionState();

        if (state.cooldownRemaining > 0) {
            state.cooldownRemaining--;
            log.info("压缩冷却中（剩余 {} 次跳过），当前占比 {}%，跳过压缩",
                    state.cooldownRemaining,
                    String.format("%.1f", ratio * 100));
            return messages;
        }

        if (state.ineffectiveCount >= MAX_INEFFECTIVE_COMPRESSIONS) {
            log.warn("连续 {} 次压缩节省不到 10%，跳过。建议开新会话。", state.ineffectiveCount);
            return messages;
        }

        log.info("上下文占比 {}% >= {}%，开始压缩 {} 条消息",
                String.format("%.1f", ratio * 100),
                String.format("%.0f", compressionThreshold * 100),
                messages.size());

        return doCompress(messages, maxTokens, state);
    }

    private List<ChatMessage> doCompress(List<ChatMessage> messages, int maxTokens, SessionState state) {
        // 保留原始引用：若压缩无效或边界不合理，原样返回，不丢失任何内容。
        List<ChatMessage> original = messages;

        // 阶段 1
        messages = pruneOldToolResults(messages);

        // 阶段 2：保护区 = 前置 system/taskPlan + 首个真实用户意图（含其后的图片等内容）。
        // 锚定用户原始诉求，确保多轮压缩后模型始终看得到「最初要做什么」。
        int headEnd = computeHeadEnd(messages);
        int cutIdx = findTailCutIndex(messages, headEnd, maxTokens);
        if (headEnd >= cutIdx) { log.warn("边界不合理，放弃压缩"); return original; }

        List<ChatMessage> toSummarize = messages.subList(headEnd, cutIdx);
        List<ChatMessage> tail = messages.subList(cutIdx, messages.size());

        log.info("压缩边界: 头部 {} + 中间 {} + 尾部 {}", headEnd, toSummarize.size(), tail.size());

        // 阶段 3: 合并摘要生成 + 记忆提取（单次 LLM 调用）
        int summaryBudget = Math.min(MAX_SUMMARY_TOKENS, (int)(maxTokens * 0.05));
        String summary = generateSummaryAndExtractMemory(toSummarize, summaryBudget, state);

        if (summary == null || summary.isBlank()) {
            summary = String.format("【上下文压缩】%d 条对话轮次已移除。请基于最近对话继续。", toSummarize.size());
        }

        // 阶段 4：摘要以 SystemMessage 注入（背景信息，而非用户输入），避免模型误当成用户发言。
        List<ChatMessage> compressed = new ArrayList<>();
        for (int i = 0; i < headEnd; i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof SystemMessage sm && !sm.text().contains("已被压缩为交接摘要")) {
                msg = new SystemMessage(sm.text() + "\n\n[注意：之前对话已压缩为交接摘要，请基于摘要和最近对话继续。]");
            }
            compressed.add(msg);
        }
        compressed.add(new SystemMessage("【历史对话摘要】以下是更早对话的交接摘要，作为背景参考：\n" + summary));
        compressed.addAll(tail);

        // 阶段 5
        compressed = sanitizeOrphanedToolPairs(compressed);

        // 统计 & 防抖动
        int before = tokenEstimator.estimateMessages(original);
        int after = tokenEstimator.estimateMessages(compressed);
        double savingsPct = before > 0 ? (double)(before - after) / before * 100 : 0;

        log.info("压缩完成: {} → {} 条, token {} → {} (节省 {}%)",
                original.size(), compressed.size(), before, after, String.format("%.1f", savingsPct));

        if (savingsPct < 10) {
            state.ineffectiveCount++;
        } else {
            state.ineffectiveCount = 0;
        }

        // 如果压缩后 token 反而增加了，进入冷却期（跳过后续 3 次压缩机会），并原样返回未压缩内容。
        if (after >= before) {
            state.cooldownRemaining = 3;
            state.lastCompressionIncreasedTokens = true;
            log.warn("压缩后 token 反而增加 ({}→{})，进入 3 次冷却期", before, after);
            return original;
        }
        state.lastCompressionIncreasedTokens = false;
        return compressed;
    }

    /**
     * 计算保护区边界：保护前置 system/taskPlan 消息 + 首个真实用户意图。
     * 这样多轮压缩后，模型始终能看到「用户最初要做什么」，不会被摘要冲掉。
     */
    private int computeHeadEnd(List<ChatMessage> messages) {
        // 跳过开头连续的 SystemMessage（system prompt + taskPlan 块）
        int i = 0;
        while (i < messages.size() && messages.get(i) instanceof SystemMessage) i++;
        // 把首个用户消息纳入保护区（headEnd 为排他上界，故 +1）
        if (i < messages.size() && messages.get(i) instanceof UserMessage) {
            return Math.min(i + 1, messages.size());
        }
        // 没有可识别的首个用户消息：退回原行为，至少保护前 PROTECT_FIRST_N 条
        return Math.min(PROTECT_FIRST_N, messages.size());
    }

    // ─── 工具方法 ───
    private String extractText(UserMessage um) {
        if (um.hasSingleText()) return um.singleText();
        StringBuilder sb = new StringBuilder();
        um.contents().forEach(c -> {
            if (c instanceof dev.langchain4j.data.message.TextContent tc) {
                sb.append(tc.text());
            }
        });
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        return s != null && s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
