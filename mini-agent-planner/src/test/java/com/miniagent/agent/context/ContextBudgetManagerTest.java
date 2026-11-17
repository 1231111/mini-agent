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

    @Test
    void fit与最终prompt使用完全相同的预算截断() {
        ContextBudgetProperties props = new ContextBudgetProperties();
        props.setEnabled(true);
        props.setCharsPerToken(1.0);
        props.getSlotTokens().put("memory", 40);
        ContextBudgetManager manager = new ContextBudgetManager(props);
        String memory = "本次注入记忆内容需要被截断".repeat(4);

        List<ContextFragment> fitted = manager.fit(List.of(
                ContextFragment.of(ContextSlot.MEMORY, memory)));

        assertEquals(manager.assemble(List.of(ContextFragment.of(
                ContextSlot.MEMORY, memory))), manager.assembleFitted(fitted));
        assertTrue(fitted.get(0).text().endsWith("…"));
    }

    @Test
    void buildDetailed保留送入模型的最终槽位文本() {
        ContextBudgetManager budget = new ContextBudgetManager(new ContextBudgetProperties());
        ContextBuilder builder = new ContextBuilder(List.of(
                context -> ContextFragment.of(ContextSlot.MEMORY, "<memory>实际注入内容</memory>")), budget);

        ContextBuildResult result = builder.buildDetailed(new ContextBuildContext(
                "session", "query", ContextLoadPolicy.defaults(), java.util.Set.of(), null, false, false));

        assertEquals("<memory>实际注入内容</memory>", result.textFor(ContextSlot.MEMORY));
        assertEquals(result.textFor(ContextSlot.MEMORY), result.systemPrompt());
    }
}
