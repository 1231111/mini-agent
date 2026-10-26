package com.miniagent.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopRetrievalCapTest {

    @Test
    void retrievalToolsAreRecognized() {
        assertTrue(AgentLoop.isRetrievalTool("web_search"));
        assertTrue(AgentLoop.isRetrievalTool("browser_navigate"));
        assertFalse(AgentLoop.isRetrievalTool("exec_command"));
        assertFalse(AgentLoop.isRetrievalTool("read_file"));
    }

    @Test
    void retrievalClosesAtCapForEveryTask() {
        assertFalse(AgentLoop.closeRetrieval(0));
        assertFalse(AgentLoop.closeRetrieval(AgentLoop.RETRIEVAL_CALL_CAP - 1));
        assertTrue(AgentLoop.closeRetrieval(AgentLoop.RETRIEVAL_CALL_CAP));
        assertTrue(AgentLoop.closeRetrieval(AgentLoop.RETRIEVAL_CALL_CAP + 5));
    }
}
