package com.miniagent.agent.tool;

/** 工具层错误码，供执行器和 Planner 消费。 */
public enum ToolErrorCode {
    NONE,
    INVALID_ARGUMENT,
    NOT_FOUND,
    PERMISSION_DENIED,
    DEPENDENCY_UNAVAILABLE,
    TIMEOUT,
    RATE_LIMITED,
    /** 共享资源被占用（锁没等到），稍后可安全重试 —— 与 TIMEOUT（跑过了预算）语义不同。 */
    RESOURCE_BUSY,
    CONFLICT,
    CANCELLED,
    UNKNOWN_TOOL,
    EMPTY_RESULT,
    OUTCOME_UNKNOWN,
    EXECUTION_FAILED,
    INTERNAL_ERROR
}
