package com.miniagent.agent.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextBudgetManagerTest {

    @Test
    void 超预算按槽位截断() {
        ContextBudgetProperties props = new ContextBudgetProperties();
        props.setEnabled(true);
        props.setCharsPerToken(1.0);
        props.getSlotTokens().put("memory", 10);

        ContextBudgetManager manager = new ContextBudgetManager(props);
        String longText = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOP";
        String assembled = manager.assemble(List.of(
                ContextFragment.of(ContextSlot.MEMORY, longText)));

        assertTrue(assembled.endsWith("…"));
        assertTrue(assembled.length() <= 34);
        assertFalse(assembled.contains("ABCDEF"));
    }

    @Test
    void 关闭预算时不截断() {
        ContextBudgetProperties props = new ContextBudgetProperties();
        props.setEnabled(false);
        ContextBudgetManager manager = new ContextBudgetManager(props);
        String text = "a".repeat(5000);
        String assembled = manager.assemble(List.of(
                ContextFragment.of(ContextSlot.MEMORY, text)));
        assertEquals(text, assembled);
    }

    @Test
    void 空块丢弃() {
        ContextBudgetManager manager = new ContextBudgetManager(new ContextBudgetProperties());
        assertEquals("", manager.assemble(List.of(
                ContextFragment.of(ContextSlot.MEMORY, "  "))));
    }
}
