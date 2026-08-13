package com.miniagent.memory.lifecycle;

import com.miniagent.memory.model.MemoryEntry;
import com.miniagent.memory.model.MemoryStatus;

/**
 * 遗忘策略：评估记忆是否应该被归档或删除。
 */
public interface ForgettingPolicy {

    /**
     * 评估记忆的保留状态。
     *
     * @return 建议的状态（ACTIVE / ARCHIVED / DELETED）
     */
    MemoryStatus evaluate(MemoryEntry memory);
}
