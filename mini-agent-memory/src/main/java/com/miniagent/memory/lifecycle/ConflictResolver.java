package com.miniagent.memory.lifecycle;

import com.miniagent.memory.model.MemoryEntry;

/**
 * 冲突解决器：当新记忆和已有记忆冲突时，决定如何处理。
 */
public interface ConflictResolver {

    /**
     * 解决冲突。
     *
     * @param existing 已有记忆
     * @param incoming 新记忆
     * @return 合并后的记忆（或 null 表示丢弃新记忆）
     */
    MemoryEntry resolve(MemoryEntry existing, MemoryEntry incoming);
}
