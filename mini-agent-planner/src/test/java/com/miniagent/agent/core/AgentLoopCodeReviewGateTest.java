package com.miniagent.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopCodeReviewGateTest {
    @Test
    void codeFileWritesRequireReviewEvenWhenTaskWordingIsAmbiguous() {
        assertTrue(AgentLoop.requiresCodeReviewForFileWrite("edit_file",
                "{\"path\":\"src/cache.py\",\"oldString\":\"\",\"newString\":\"\"}"));
        assertTrue(AgentLoop.requiresCodeReviewForFileWrite("write_file",
                "{\"path\":\"src/Main.java\",\"content\":\"class Main {}\"}"));
        assertTrue(AgentLoop.requiresCodeReviewForFileWrite("write_file",
                "{\"path\":\"pom.xml\",\"content\":\"<project/>\"}"));
        assertTrue(AgentLoop.requiresCodeReviewForFileWrite("edit_file",
                "{\"path\":\"package.json\",\"oldString\":\"{}\",\"newString\":\"{}\"}"));
    }

    @Test
    void nonCodeDocumentsAndOtherToolsDoNotArmCodeReview() {
        assertFalse(AgentLoop.requiresCodeReviewForFileWrite("write_file",
                "{\"path\":\"notes.md\",\"content\":\"notes\"}"));
        assertFalse(AgentLoop.requiresCodeReviewForFileWrite("write_file",
                "{\"path\":\"fixtures/sample.json\",\"content\":\"{}\"}"));
        assertFalse(AgentLoop.requiresCodeReviewForFileWrite("read_file",
                "{\"path\":\"src/Main.java\"}"));
    }
}
