package com.miniagent.agent.intent;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class IntentRuleLearningServiceTest {

    @Test
    void suggestPattern_quotesLiteral() {
        String p = IntentRuleLearningService.suggestPattern("打开飞书 wiki 写文档");
        assertTrue(p.startsWith("(?i)"));
        assertDoesNotThrow(() -> Pattern.compile(p));
        assertTrue(Pattern.compile(p).matcher("打开飞书 wiki 写文档").find()
                || Pattern.compile(p).matcher("飞书").find());
    }

    @Test
    void signalGroupForIntent_mapsKnown() {
        assertEquals(IntentRuleRuntime.GROUP_QUESTION,
                IntentRuleLearningService.signalGroupForIntent("QUESTION", "WRONG_INTENT"));
        assertEquals(IntentRuleRuntime.GROUP_PURE_IMAGE,
                IntentRuleLearningService.signalGroupForIntent("IMAGE_GENERATION", "MISSED_RULE"));
        assertEquals(IntentRuleRuntime.GROUP_COMPLEX,
                IntentRuleLearningService.signalGroupForIntent("NEW_TASK", "WRONG_INTENT"));
    }
}
