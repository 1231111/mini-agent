package com.miniagent.memory.writer;

import com.miniagent.memory.model.AgentEvent;

/**
 * 重要度评估器：判断一个事件是否值得记住，以及重要程度。
 */
public interface ImportanceEvaluator {

    /**
     * 评估事件的重要性。返回 0.0 ~ 1.0。
     * 低于阈值（如 0.3）的事件不会被记住。
     */
    double evaluate(AgentEvent event);
}
