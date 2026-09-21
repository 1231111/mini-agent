package com.miniagent.agent.context;

import com.miniagent.agent.task.TaskPlan;

import java.util.Set;

/**
 * 一轮 system prompt 组装入参。贡献者只读此对象。
 *
 * @param hasMedia 本轮用户消息是否带图片/音视频。只用于决定「点评轮」那段附加提示词
 *                 要不要拼进去，<b>不参与</b>工具清单、历史条数、执行路径的判定 ——
 *                 那些资源一旦按它收窄就是不可恢复的。
 */
public record ContextBuildContext(
        String sessionId,
        String query,
        ContextLoadPolicy policy,
        Set<String> toolNames,
        ContextReferenceDecision reference,
        TaskPlan taskPlan,
        boolean hasMedia
) {

    /**
     * 纯问答轮：问的是能力/寒暄/算术，且没有任何要动手的信号。
     */
    public boolean lightTurn() {
        return taskPlan != null && taskPlan.signals().lightTurn();
    }

    /**
     * 点评轮：用户带了媒体（截图等），而且没有命中任何要动手的信号。
     *
     * <p>判据两条都是可复核的事实 —— 消息里到底有没有媒体、这句话里有没有出现要求动手的词。
     * 没有「用户想干什么」的推断成分。</p>
     */
    public boolean reviewTurn() {
        return hasMedia && (taskPlan == null || !taskPlan.signals().actionBearing());
    }
}
