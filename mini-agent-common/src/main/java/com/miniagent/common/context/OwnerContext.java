package com.miniagent.common.context;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** User and tenant identity propagated across agent and tool execution threads. */
public record OwnerContext(Long userId, String tenantId) {
    private static final ThreadLocal<Long> CURRENT_USER = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public static OwnerContext capture() {
        return new OwnerContext(CURRENT_USER.get(), CURRENT_TENANT.get());
    }

    public static void setCurrentUser(Long userId) {
        if (userId == null) {
            CURRENT_USER.remove();
        } else {
            CURRENT_USER.set(userId);
        }
    }

    public static void clearCurrentUser() {
        CURRENT_USER.remove();
    }

    public static Long currentUserId() {
        return CURRENT_USER.get();
    }

    public static void setCurrentTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            CURRENT_TENANT.remove();
        } else {
            CURRENT_TENANT.set(tenantId.trim());
        }
    }

    public static void clearCurrentTenant() {
        CURRENT_TENANT.remove();
    }

    public static String currentTenantId() {
        return CURRENT_TENANT.get();
    }

    public static String effectiveTenantId() {
        String tenantId = currentTenantId();
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId;
    }

    public static String effectiveUserId() {
        Long userId = currentUserId();
        return String.valueOf(userId == null ? -1L : userId);
    }

    public static Scope bind(OwnerContext context) {
        OwnerContext previous = capture();
        OwnerContext next = Objects.requireNonNullElse(context, new OwnerContext(null, null));
        restore(next);
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                restore(previous);
            }
        };
    }

    private static void restore(OwnerContext context) {
        if (context.userId() == null) {
            CURRENT_USER.remove();
        } else {
            CURRENT_USER.set(context.userId());
        }
        if (context.tenantId() == null || context.tenantId().isBlank()) {
            CURRENT_TENANT.remove();
        } else {
            CURRENT_TENANT.set(context.tenantId().trim());
        }
    }
}
