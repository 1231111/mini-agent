package com.miniagent.config.entity;

/** Roles are intentionally coarse; resource ownership is enforced separately. */
public enum UserRole {
    USER,
    TENANT_ADMIN,
    SYSTEM_ADMIN
}
