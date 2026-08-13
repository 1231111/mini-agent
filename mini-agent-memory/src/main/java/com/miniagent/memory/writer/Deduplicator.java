package com.miniagent.memory.writer;

import com.miniagent.memory.model.MemoryEntry;

import java.util.Optional;

/**
 * 去重器：检测新记忆是否和已有记忆重复。
 */
public interface Deduplicator {

    /**
     * 查找与新记忆重复的已有记忆。
     * 返回重复记忆（如果找到），调用方决定是合并还是替换。
     */
    Optional<MemoryEntry> findDuplicate(MemoryEntry newEntry);
}
