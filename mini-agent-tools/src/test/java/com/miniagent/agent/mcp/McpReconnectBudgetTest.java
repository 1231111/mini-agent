package com.miniagent.agent.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpReconnectBudgetTest {
    @Test
    void boundsRestartsPerServerAndReleasesAttemptsAfterWindow() {
        McpReconnectBudget budget = new McpReconnectBudget();
        assertTrue(budget.allow("filesystem", 1_000L, 60_000L, 2));
        assertTrue(budget.allow("filesystem", 2_000L, 60_000L, 2));
        assertFalse(budget.allow("filesystem", 3_000L, 60_000L, 2));
        assertTrue(budget.allow("browser", 3_000L, 60_000L, 2), "servers have independent budgets");
        assertEquals(2, budget.attemptsInWindow("filesystem", 3_000L, 60_000L));

        assertTrue(budget.allow("filesystem", 62_001L, 60_000L, 2));
        assertEquals(1, budget.attemptsInWindow("filesystem", 62_001L, 60_000L));
    }
}
