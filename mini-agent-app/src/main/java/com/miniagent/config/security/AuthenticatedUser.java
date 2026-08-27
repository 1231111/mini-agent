package com.miniagent.config.security;

import com.miniagent.config.entity.UserRole;

import java.io.Serializable;

public record AuthenticatedUser(
        Long userId,
        Long tenantId,
        String username,
        UserRole role
) implements Serializable {
    public String tenantKey() {
        return String.valueOf(tenantId);
    }
}
