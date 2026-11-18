package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;
import com.miniagent.agent.tool.impl.AskUserQuestionParams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AskUserQuestionToolTest {

    @Test
    void displayTextIncludesQuestionAndOptions() {
        String args = "{\"question\":\"主题？\","
                + "\"options\":\"[\\\"爱情 - 流行\\\",\\\"自由发挥\\\"]\"}";
        String text = AskUserQuestionTool.displayText(args);
        assertTrue(text.contains("主题？"));
        assertTrue(text.contains("爱情 - 流行"));
        assertTrue(text.contains("自由发挥"));
    }

    @Test
    void sseJsonDoesNotBlock() {
        long start = System.nanoTime();
        String json = AskUserQuestionTool.sseJson(
                "{\"question\":\"风格？\",\"options\":[\"民谣\",\"摇滚\"]}");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 1000, "yield must not wait, took " + elapsedMs + "ms");
        assertTrue(json.contains("风格？"));
        assertTrue(json.contains("民谣"));
        assertFalse(json.contains("error"));
    }

    @Test
    void askUserIsReadOnlySessionScoped() {
        assertEquals(ToolSideEffect.READ_ONLY,
                ToolConcurrencyPolicy.sideEffectOf("ask_user_question"));
        assertEquals(ToolConcurrencyScope.SESSION,
                ToolConcurrencyPolicy.concurrencyScopeOf("ask_user_question"));
    }

    @Test
    void naturalArrayOptionsAreTypedAndVisibleInTheToolSchema() {
        AskUserQuestionParams params = ToolParams.fromJson(
                "{\"question\":\"风格？\",\"options\":[\"民谣\",\"摇滚\"]}",
                AskUserQuestionParams.class);

        assertEquals(java.util.List.of("民谣", "摇滚"), params.getOptions());
        var schema = ToolParams.generateSchema(AskUserQuestionParams.class);
        assertEquals("array", ((java.util.Map<?, ?>) schema.get("options")).get("type"));
        assertTrue(ToolArgumentValidator.validate(schema,
                "{\"question\":\"风格？\",\"options\":[\"民谣\",\"摇滚\"]}").valid());
    }
}
