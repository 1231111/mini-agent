package com.miniagent.memory;

import com.miniagent.common.context.OwnerContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MemoryStoreOwnerContextTest {

    @AfterEach
    void clearContext() {
        OwnerContext.clearCurrentUser();
        OwnerContext.clearCurrentTenant();
    }

    @Test
    void nestedBindingRestoresThePreviousOwner() {
        OwnerContext.setCurrentUser(1L);
        OwnerContext.setCurrentTenant("tenant-a");

        try (var ignored = OwnerContext.bind(
                new OwnerContext(2L, "tenant-b"))) {
            assertEquals(2L, OwnerContext.currentUserId());
            assertEquals("tenant-b", OwnerContext.currentTenantId());
        }

        assertEquals(1L, OwnerContext.currentUserId());
        assertEquals("tenant-a", OwnerContext.currentTenantId());
    }

    @Test
    void pooledThreadDoesNotLeakThePreviousOwner() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                try (var ignored = OwnerContext.bind(
                        new OwnerContext(9L, "tenant-nine"))) {
                    assertEquals(9L, OwnerContext.currentUserId());
                }
            }).get(5, TimeUnit.SECONDS);

            OwnerContext observed = executor.submit(
                    OwnerContext::capture).get(5, TimeUnit.SECONDS);
            assertNull(observed.userId());
            assertNull(observed.tenantId());
        } finally {
            executor.shutdownNow();
        }
    }
}
