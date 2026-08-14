package com.miniagent.common;

/**
 * 统一运行状态枚举，替换散落在各处的 "SUCCESS" / "FAILURE" / "RUNNING" 等魔法字符串。
 */
public enum RunStatus {
    SUCCESS,
    FAILURE,
    RUNNING,
    COMPLETED,
    FAILED,
    INTERRUPTED,
    TIMEOUT,
    PENDING,
    CANCELED,
    ABORTED,
    WAITING,
    REJECTED,
    UNKNOWN
}
