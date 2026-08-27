package com.miniagent.memory.writer;

import com.miniagent.memory.model.AgentEvent;
import com.miniagent.memory.model.MemoryType;

/**
 * 记忆分类器：判断事件应该归类为哪种记忆类型。
 */
public interface MemoryClassifier {

    /**
     * 对事件进行分类。
     */
    MemoryType classify(AgentEvent event);
}
