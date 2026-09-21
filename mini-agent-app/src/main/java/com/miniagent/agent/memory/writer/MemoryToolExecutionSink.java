package com.miniagent.agent.memory.writer;

import com.miniagent.agent.core.ToolExecutionSink;
import com.miniagent.agent.tool.ToolStatus;
import com.miniagent.memory.MemoryManager;
import com.miniagent.memory.MemoryStore;
import com.miniagent.memory.model.AgentEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把主循环上报的工具执行事实落成 {@link AgentEvent.EventType#TOOL_EXECUTION} 事件。
 *
 * <p>在整条记忆链路上，这个类是<b>唯一</b>往事件表写 {@code TOOL_EXECUTION} 的地方。
 * 缺了它，下游三处全部读到空集：</p>
 * <ul>
 *   <li>{@code RuleBasedImportanceEvaluator} —— 「工具执行失败 +0.2」这一档永远加不上；</li>
 *   <li>{@code DefaultEventProcessor.buildSummary} —— 摘要分支走不到；</li>
 *   <li>{@code DefaultConsolidationService.buildFallbackSummary} —— 统计恒为
 *       「执行了 0 个工具调用，0 个失败」；
 *       {@code actions} 回填也从 {@code TOOL_EXECUTION} 的 payload 取 {@code tool}，
 *       取不到就只能落一个空的 actions 数组。</li>
 * </ul>
 *
 * <p><b>为什么 payload 里 error 与 result 二选一</b>：
 * {@code DefaultEventProcessor.buildContent} 会把两个键都渲染出来
 * （{@code [TOOL_EXECUTION] 工具: x 错误: y 结果: z}），
 * 失败时同时塞同一段文本会重复两遍。失败写 {@code error}、成功写 {@code result}，
 * 与既有的事件构造约定一致，也让重要度评估器能通过 {@code error} 这一键命中 +0.15。</p>
 *
 * <p>上报失败只降级为 debug 日志：记忆是旁路，不能因为它挂掉而中断正在跑的任务。</p>
 */
@Component
public class MemoryToolExecutionSink implements ToolExecutionSink {

    private static final Logger log = LoggerFactory.getLogger(MemoryToolExecutionSink.class);

    @Autowired(required = false)
    private MemoryManager memoryManager;

    @Override
    public void onToolExecuted(String sessionId, int turn, String toolName,
                               ToolStatus status, String resultText) {
        if (memoryManager == null) {
            return;
        }
        boolean failed = status != ToolStatus.SUCCESS;
        try {
            AgentEvent event = new AgentEvent();
            event.setTenantId(MemoryStore.effectiveTenantId());
            event.setSessionId(sessionId);
            event.setEventType(AgentEvent.EventType.TOOL_EXECUTION);
            event.setActor("executor");
            event.setStatus(failed
                    ? AgentEvent.EventStatus.FAILED
                    : AgentEvent.EventStatus.SUCCESS);
            event.setPayload(buildPayload(toolName, turn, failed, resultText));
            memoryManager.recordEvent(event);
        } catch (Exception e) {
            log.debug("工具执行事件上报失败: {} {}", toolName, e.getMessage());
        }
    }

    private Map<String, Object> buildPayload(String toolName, int turn,
                                             boolean failed, String resultText) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", toolName);
        payload.put("turn", turn);
        if (failed) {
            payload.put("error", resultText);
        } else {
            payload.put("result", resultText);
        }
        return payload;
    }
}
