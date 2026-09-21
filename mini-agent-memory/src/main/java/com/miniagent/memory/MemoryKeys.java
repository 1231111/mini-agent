package com.miniagent.memory;

/**
 * 记忆写入目标与语义事实键。禁止在调用点散落字面量。
 */
public final class MemoryKeys {

    public static final String TARGET_MEMORY = "memory";
    public static final String TARGET_USER = "user";
    public static final String SUBJECT_USER = "user";
    public static final String SUBJECT_TASK = "task";
    public static final String PREDICATE_PREFERENCE = "preference";
    public static final String PREDICATE_LEARNED = "learned";
    public static final String SOURCE_MEMORY_TOOL = "memory_tool";
    public static final String SOURCE_CONSOLIDATION = "consolidation";

    private MemoryKeys() {}
}
