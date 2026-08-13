package com.miniagent.memory.model;

/**
 * 记忆类型。类型和 Scope 是两个维度。
 * 例如 SEMANTIC + PROJECT = 项目级事实，SEMANTIC + USER = 用户级事实。
 */
public enum MemoryType {
    /** 当前任务执行状态（Working Memory） */
    WORKING,
    /** 情景记忆：发生过什么（Task → Action → Observation → Result） */
    EPISODIC,
    /** 语义记忆：稳定事实（三元组 Subject-Predicate-Object） */
    SEMANTIC,
    /** 程序性记忆：可复用 SOP / Playbook */
    PROCEDURAL,
    /** 用户画像：偏好、风格、习惯 */
    USER,
    /** 项目级上下文：技术栈、约定、配置 */
    PROJECT,
    /** 组织级知识 */
    ORGANIZATION
}
