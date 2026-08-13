package com.miniagent.agent.memory.writer;

import com.miniagent.memory.model.AgentEvent;
import com.miniagent.memory.model.MemoryType;
import com.miniagent.memory.writer.MemoryClassifier;
import org.springframework.stereotype.Component;

/**
 * 基于规则的记忆分类器（规则优先，只在不确定时才调 LLM）。
 */
@Component
public class RuleBasedMemoryClassifier implements MemoryClassifier {

    @Override
    public MemoryType classify(AgentEvent event) {
        // 用户反馈 → 用户偏好
        if (event.getEventType() == AgentEvent.EventType.USER_FEEDBACK) {
            return MemoryType.USER;
        }

        // 工具执行 → 情景记忆
        if (event.getEventType() == AgentEvent.EventType.TOOL_EXECUTION) {
            return MemoryType.EPISODIC;
        }

        // 任务完成/失败 → 情景记忆
        if (event.getEventType() == AgentEvent.EventType.TASK_COMPLETE
            || event.getEventType() == AgentEvent.EventType.TASK_FAIL) {
            return MemoryType.EPISODIC;
        }

        // 错误 → 情景记忆（失败经验）
        if (event.getEventType() == AgentEvent.EventType.ERROR) {
            return MemoryType.EPISODIC;
        }

        // 计划变更 → 语义记忆（事实变化）
        if (event.getEventType() == AgentEvent.EventType.PLAN_CHANGE) {
            return MemoryType.SEMANTIC;
        }

        // payload 中有可复用模式 → 程序性记忆
        if (event.getPayload() != null) {
            Object reusable = event.getPayload().get("reusable");
            if (Boolean.TRUE.equals(reusable)) {
                return MemoryType.PROCEDURAL;
            }
            // 有配置/事实类信息 → 语义记忆
            Object fact = event.getPayload().get("fact");
            if (fact != null) {
                return MemoryType.SEMANTIC;
            }
        }

        // 默认归为情景记忆
        return MemoryType.EPISODIC;
    }
}
