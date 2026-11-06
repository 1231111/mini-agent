package com.miniagent.agent.task;

import com.miniagent.common.MessageConstants;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把用户消息变成 {@link TaskPlan}。
 *
 * <p>它只做两件事：去掉首尾空白、识别系统控制语。不做分类、不提取信号、
 * 不判断任务复杂度 —— 这些判断在 2026-10-22 随词表层一起删除。
 * 需要拆解时由模型在 {@code AgentLoop} 内自行决定。</p>
 */
@Component
public class TaskPlanFactory {

    /**
     * 组装本轮任务参数。
     *
     * @param userMessage 用户消息原文；为 {@code null} 时按空消息处理
     */
    public TaskPlan build(String userMessage) {
        String text = userMessage == null ? "" : userMessage.trim();
        // 系统控制语不是用户任务。前端「已批准「X」，请继续执行。」这类文本由代码拼出来、
        // 当用户消息发回来，里面必然带着工具名（write_file 等）。它只说明上一步被放行，
        // 不构成任何新交付目标，因此单独标注来源，便于轨迹区分。
        if (MessageConstants.isSystemControlMessage(text)) {
            return new TaskPlan(text, null, List.of(), "system-control");
        }
        return new TaskPlan(text, null, List.of(), "user-message");
    }
}
