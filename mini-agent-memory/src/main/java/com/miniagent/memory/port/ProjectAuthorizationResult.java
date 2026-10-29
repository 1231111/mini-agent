package com.miniagent.memory.port;

/** 项目授权快照投影结果。 */
public record ProjectAuthorizationResult(Status status, long currentVersion) {
    public enum Status { CREATED, UPDATED, UNCHANGED, STALE, CONFLICT }
}
