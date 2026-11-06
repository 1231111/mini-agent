package com.miniagent.agent.context;

import java.util.List;

/**
 * 指代判定结果：粗筛闸门，供 ContextLoader 决定是否/如何捞历史。
 */
public record ContextReferenceDecision(
        boolean hasReference,
        double confidence,
        List<String> candidates,
        boolean isNegated
) {
    public static ContextReferenceDecision none() {
        return new ContextReferenceDecision(false, 0, List.of(), false);
    }

    /** 有指代且未被否定时才应加载先前上下文 */
    public boolean shouldLoadPriorHistory() {
        return hasReference && !isNegated;
    }
}
