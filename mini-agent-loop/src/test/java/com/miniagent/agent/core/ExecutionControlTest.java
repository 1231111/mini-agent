package com.miniagent.agent.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionControlTest {

    @Test
    void tenantQuotaStopsTheLeaseAfterUsageCrossesTheLimit() {
        AtomicLong used = new AtomicLong();
        TenantTokenQuota quota = new TenantTokenQuota() {
            @Override
            public Decision check(Long tenantId) {
                long value = used.get();
                return new Decision(value < 5, value, 5);
            }

            @Override
            public Decision consume(Long tenantId, long tokens) {
                long value = used.addAndGet(tokens);
                return new Decision(value <= 5, value, 5);
            }
        };
        ExecutionControl control = new ExecutionControl(
                60_000, 10, 10_000, Optional.empty(), Optional.of(quota));
        control.start("quota-session", 42L);

        assertEquals(ExecutionControl.StopReason.NONE,
                control.afterModelTokens("quota-session", 3));
        assertEquals(ExecutionControl.StopReason.TENANT_QUOTA_EXCEEDED,
                control.afterModelTokens("quota-session", 3));
        assertEquals(ExecutionControl.StopReason.TENANT_QUOTA_EXCEEDED,
                control.afterModelTokens("quota-session", 0));
    }

    @Test
    void childUsesParentLeaseForCancelAndToolBudget() {
        ExecutionControl control = new ExecutionControl(60_000, 1, 10_000);
        control.start("parent");
        control.attach("parent:sub:1", "parent");

        assertEquals(ExecutionControl.StopReason.NONE, control.beforeTool("parent"));
        assertEquals(ExecutionControl.StopReason.TOOL_BUDGET_EXCEEDED,
                control.beforeTool("parent:sub:1"));

        ExecutionControl cancelled = new ExecutionControl(60_000, 10, 10_000);
        cancelled.start("parent");
        cancelled.attach("parent:sub:1", "parent");
        cancelled.cancel("parent");
        assertEquals(ExecutionControl.StopReason.CANCELLED,
                cancelled.beforeTool("parent:sub:1"));
    }
}
