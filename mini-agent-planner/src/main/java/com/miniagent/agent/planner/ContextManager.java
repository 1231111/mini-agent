package com.miniagent.agent.planner;

import com.miniagent.agent.intent.TaskPlan;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上下文分层管理器，避免上下文污染和信息丢失。
 *
 * <p>上下文分层：
 * <ul>
 *   <li><b>全局层（Global）</b>：任务目标、用户原始消息、系统提示词 - 整个任务生命周期不变</li>
 *   <li><b>规划层（Planning）</b>：Goal、TaskGraph、约束条件 - 编译后基本不变</li>
 *   <li><b>节点层（Node）</b>：当前执行节点、前置节点输出 - 每个节点执行时变化</li>
 *   <li><b>工具层（Tool）</b>：工具参数、工具路由、验收标准 - 每次工具调用时变化</li>
 * </ul>
 *
 * <p>设计原则：
 * <ul>
 *   <li>层级单向依赖：上层不依赖下层</li>
 *   <li>下层可以读取上层，但不能修改</li>
 *   <li>每层有独立的生命周期和缓存策略</li>
 *   <li>支持上下文快照和恢复</li>
 * </ul>
 */
@Component
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    /** 全局上下文缓存（session -> context） */
    private final Map<String, GlobalContext> globalContexts = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, GlobalContext> eldest) {
            return size() > 100; // 最多缓存 100 个会话
        }
    };

    /**
     * 全局上下文 - 整个任务生命周期不变。
     */
    public record GlobalContext(
            String sessionId,
            String userMessage,
            String systemPrompt,
            TaskPlan taskPlan,
            Goal goal,
            long createdAt
    ) {
        public static GlobalContext create(String sessionId, String userMessage,
                                          String systemPrompt, TaskPlan taskPlan, Goal goal) {
            return new GlobalContext(
                    sessionId,
                    userMessage,
                    systemPrompt,
                    taskPlan,
                    goal,
                    System.currentTimeMillis()
            );
        }
    }

    /**
     * 节点上下文 - 每个节点执行时变化。
     */
    public record NodeContext(
            String taskId,
            String taskName,
            String capability,
            Map<String, String> predecessorOutputs,
            Map<String, String> outputBindings,
            long startedAt
    ) {
        public static NodeContext create(String taskId, String taskName, String capability,
                                        Map<String, String> predecessorOutputs) {
            return new NodeContext(
                    taskId,
                    taskName,
                    capability,
                    predecessorOutputs != null ? predecessorOutputs : Map.of(),
                    Map.of(),
                    System.currentTimeMillis()
            );
        }

        /**
         * 获取前置输出的摘要（避免上下文过长）。
         */
        public String predecessorSummary(int maxChars) {
            if (predecessorOutputs.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> entry : predecessorOutputs.entrySet()) {
                String value = entry.getValue();
                if (value == null || value.isBlank()) {
                    continue;
                }
                if (sb.length() + value.length() > maxChars) {
                    sb.append("... (已截断)");
                    break;
                }
                sb.append("## ").append(entry.getKey()).append("\n");
                sb.append(value).append("\n\n");
            }
            return sb.toString();
        }
    }

    /**
     * 工具上下文 - 每次工具调用时变化。
     */
    public record ToolContext(
            String toolName,
            Map<String, Object> arguments,
            String idempotencyKey,
            int timeoutSeconds,
            String acceptanceCriteria
    ) {
        public static ToolContext create(String toolName, Map<String, Object> arguments) {
            return new ToolContext(
                    toolName,
                    arguments != null ? arguments : Map.of(),
                    null,
                    60,
                    null
            );
        }
    }

    /**
     * 获取或创建全局上下文。
     */
    public GlobalContext getOrCreateGlobal(String sessionId, String userMessage,
                                          String systemPrompt, TaskPlan taskPlan, Goal goal) {
        return globalContexts.computeIfAbsent(sessionId,
                k -> GlobalContext.create(k, userMessage, systemPrompt, taskPlan, goal));
    }

    /**
     * 获取全局上下文。
     */
    public GlobalContext getGlobal(String sessionId) {
        return globalContexts.get(sessionId);
    }

    /**
     * 清除全局上下文。
     */
    public void clearGlobal(String sessionId) {
        globalContexts.remove(sessionId);
        log.debug("ContextManager: 清除全局上下文 session={}", sessionId);
    }

    /**
     * 构建节点执行的完整提示词。
     *
     * @param global 全局上下文
     * @param node   节点上下文
     * @return 组装好的提示词
     */
    public String buildNodePrompt(GlobalContext global, NodeContext node) {
        StringBuilder sb = new StringBuilder();

        // 全局层：任务目标（压缩）
        if (global != null) {
            sb.append("# 任务目标\n");
            sb.append("- 目标: ").append(truncate(global.goal() != null ? global.goal().objective() : "", 200)).append("\n");
            sb.append("- 意图: ").append(global.taskPlan() != null ? global.taskPlan().intent() : "UNKNOWN").append("\n");
        }

        // 节点层：当前任务
        sb.append("\n# 当前任务\n");
        sb.append("- 任务ID: ").append(node.taskId()).append("\n");
        sb.append("- 任务名称: ").append(node.taskName()).append("\n");
        sb.append("- 能力类型: ").append(node.capability()).append("\n");

        // 前置输出（截断）
        String predecessorSummary = node.predecessorSummary(3000);
        if (!predecessorSummary.isEmpty()) {
            sb.append("\n# 前置任务产出\n");
            sb.append(predecessorSummary);
        }

        return sb.toString();
    }

    /**
     * 截断字符串。
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 清理过期上下文（定期调用）。
     */
    public int cleanup(long maxAgeMs) {
        long now = System.currentTimeMillis();
        int removed = 0;
        var iterator = globalContexts.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (now - entry.getValue().createdAt() >= maxAgeMs) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("ContextManager: 清理 {} 个过期上下文", removed);
        }
        return removed;
    }
}
