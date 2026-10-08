package com.miniagent.account.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopLoginTicketStoreTest {

    @Test
    void ticketIsSingleUse() {
        DesktopLoginTicketStore store = new DesktopLoginTicketStore();
        String ticket = store.issue(7L);

        assertEquals(7L, store.consume(ticket).orElseThrow());
        assertTrue(store.consume(ticket).isEmpty());
        assertTrue(store.consume(null).isEmpty());
        assertTrue(store.consume("  ").isEmpty());
    }
}
