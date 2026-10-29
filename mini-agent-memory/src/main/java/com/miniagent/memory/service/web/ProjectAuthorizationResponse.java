package com.miniagent.memory.service.web;

public record ProjectAuthorizationResponse(Status status, long currentVersion) {
    public enum Status { CREATED, UPDATED, UNCHANGED, STALE, CONFLICT }
}
