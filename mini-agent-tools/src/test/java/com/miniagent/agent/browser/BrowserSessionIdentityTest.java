package com.miniagent.agent.browser;

import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.common.context.OwnerContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrowserSessionIdentityTest {
    @AfterEach
    void cleanup() {
        WorkspaceContext.clearSessionOverride();
        OwnerContext.clearCurrentUser();
        OwnerContext.clearCurrentTenant();
    }

    @Test
    void requiresAuthenticatedRunIdentity() {
        assertThrows(SecurityException.class, () -> BrowserSessionIdentity.resolve("default"));
        WorkspaceContext.setSessionOverride("chat-a");
        assertThrows(SecurityException.class, () -> BrowserSessionIdentity.resolve(null));
    }

    @Test
    void modelCannotSelectAnotherConversationsBrowser() {
        bind(7L, "tenant", "chat-a");
        String key = BrowserSessionIdentity.resolve(null);
        assertEquals(key, BrowserSessionIdentity.resolve("default"));
        assertEquals(key, BrowserSessionIdentity.resolve("chat-a"));
        assertThrows(SecurityException.class, () -> BrowserSessionIdentity.resolve("chat-b"));
        assertThrows(SecurityException.class, () -> BrowserSessionIdentity.resolve(key));
    }

    @Test
    void defaultNeverMergesDistinctOwnersOrChats() {
        bind(7L, "tenant", "chat-a");
        String first = BrowserSessionIdentity.resolve("default");
        bind(7L, "tenant", "chat-b");
        assertNotEquals(first, BrowserSessionIdentity.resolve("default"));
        bind(8L, "tenant", "chat-a");
        assertNotEquals(first, BrowserSessionIdentity.resolve("default"));
        bind(7L, "other-tenant", "chat-a");
        assertNotEquals(first, BrowserSessionIdentity.resolve("default"));
    }

    private void bind(long user, String tenant, String chat) {
        OwnerContext.setCurrentUser(user);
        OwnerContext.setCurrentTenant(tenant);
        WorkspaceContext.setSessionOverride(chat);
    }
}
