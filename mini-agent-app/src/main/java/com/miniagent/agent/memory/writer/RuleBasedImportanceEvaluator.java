package com.miniagent.agent.memory.writer;

import com.miniagent.memory.model.AgentEvent;
import com.miniagent.memory.writer.ImportanceEvaluator;
import org.springframework.stereotype.Component;

/**
 * 基于规则的重要性评估器（第一层，不需要 LLM）。
 *
 * 评分规则：
 * - 用户明确反馈 → +0.4
 * - 任务失败 → +0.3
 * - 可复用的操作模式 → +0.2
 * - 重复出现的模式 → +0.1
 */
@Component
public class RuleBasedImportanceEvaluator implements ImportanceEvaluator {

    @Override
    public double evaluate(AgentEvent event) {
        double score = 0.1; // 基础分

        // 用户反馈权重最高
        if (event.getEventType() == AgentEvent.EventType.USER_FEEDBACK) {
            score += 0.4;
        }

        // 任务失败比成功更有记忆价值
        if (event.getStatus() == AgentEvent.EventStatus.FAILED) {
            score += 0.3;
        }

        // 工具执行失败比成功更值得记住
        if (event.getEventType() == AgentEvent.EventType.TOOL_EXECUTION
            && event.getStatus() == AgentEvent.EventStatus.FAILED) {
            score += 0.2;
        }

        // 任务完成
        if (event.getEventType() == AgentEvent.EventType.TASK_COMPLETE) {
            score += 0.2;
        }

        // 任务失败
        if (event.getEventType() == AgentEvent.EventType.TASK_FAIL) {
            score += 0.3;
        }

        // 错误事件
        if (event.getEventType() == AgentEvent.EventType.ERROR) {
            score += 0.25;
        }

        // payload 中包含错误信息
        if (event.getPayload() != null) {
            Object error = event.getPayload().get("error");
            if (error != null) {
                score += 0.15;
            }
            // 包含可复用信息（如配置、命令）
            Object reusable = event.getPayload().get("reusable");
            if (Boolean.TRUE.equals(reusable)) {
                score += 0.2;
            }
        }

        return Math.min(1.0, score);
    }
}
