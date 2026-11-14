package com.miniagent.agent.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AskGrantDeadlineTest {
    @Test void absoluteDeadlineIsNotRoundedUpOrExtendedByDuplicateRequests() {
        SessionPermissionStore store = new SessionPermissionStore();
        long expiry = System.currentTimeMillis() + 700;
        AskGrant first = store.issueAskGrantUntil("s", "desktop_act", "{}", expiry);
        assertNotNull(first);
        assertEquals(expiry, first.expiresAtEpochMillis());
        assertEquals(first, store.issueAskGrantUntil("s", "desktop_act", "{}", expiry + 10_000));
        assertNull(store.issueAskGrantUntil("s", "desktop_act", "{}", System.currentTimeMillis()));
    }

    @Test void tighterDeadlineInvalidatesThePreviouslyLongerPendingToken() {
        SessionPermissionStore store = new SessionPermissionStore();
        AskGrant old = store.issueAskGrant("s", "desktop_act", "{}", 300_000);
        assertTrue(store.attachAskResume("s", old.token()));
        long expiry = System.currentTimeMillis() + 60_000;
        AskGrant shorter = store.issueAskGrantUntil("s", "desktop_act", "{}", expiry);
        assertEquals(expiry, shorter.expiresAtEpochMillis());
        assertNotEquals(old.token(), shorter.token());
        assertTrue(store.grantAskTokenForResume("s", old.token(), "desktop_act").isEmpty());
        assertFalse(store.hasAttachedAskResume("s", old.token()));
        assertTrue(store.attachAskResume("s", shorter.token()));
        assertTrue(store.grantAskTokenForResume("s", shorter.token(), "desktop_act").isPresent());
    }
}
