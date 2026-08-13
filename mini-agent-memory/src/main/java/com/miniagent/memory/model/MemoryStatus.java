package com.miniagent.memory.model;

/**
 * 记忆生命周期状态。
 */
public enum MemoryStatus {
    /** 活跃，可被检索 */
    ACTIVE,
    /** 已归档，不再参与常规检索，但仍可用于特殊查询 */
    ARCHIVED,
    /** 已删除（软删除），保留审计 */
    DELETED
}
