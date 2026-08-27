package com.miniagent.memory;

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
        MemoryStore.clearCurrentUser();
        MemoryStore.clearCurrentTenant();
    }

    @Test
    void nestedBindingRestoresThePreviousOwner() {
        MemoryStore.setCurrentUser(1L);
        MemoryStore.setCurrentTenant("tenant-a");

        try (var ignored = MemoryStore.bindOwnerContext(
                new MemoryStore.OwnerContext(2L, "tenant-b"))) {
            assertEquals(2L, MemoryStore.getCurrentUser());
            assertEquals("tenant-b", MemoryStore.getCurrentTenant());
        }

        assertEquals(1L, MemoryStore.getCurrentUser());
        assertEquals("tenant-a", MemoryStore.getCurrentTenant());
    }

    @Test
    void pooledThreadDoesNotLeakThePreviousOwner() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                try (var ignored = MemoryStore.bindOwnerContext(
                        new MemoryStore.OwnerContext(9L, "tenant-nine"))) {
                    assertEquals(9L, MemoryStore.getCurrentUser());
                }
            }).get(5, TimeUnit.SECONDS);

            MemoryStore.OwnerContext observed = executor.submit(
                    MemoryStore::captureOwnerContext).get(5, TimeUnit.SECONDS);
            assertNull(observed.userId());
            assertNull(observed.tenantId());
        } finally {
            executor.shutdownNow();
        }
    }
}
