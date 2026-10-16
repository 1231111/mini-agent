package com.miniagent.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopRetrievalCapTest {

    @Test
    void retrievalToolsCloseAfterCapUnlessPlanned() {
        assertTrue(AgentLoop.isRetrievalTool("web_search"));
        assertTrue(AgentLoop.isRetrievalTool("browser_navigate"));
        assertFalse(AgentLoop.isRetrievalTool("exec_command"));
        assertFalse(AgentLoop.isRetrievalTool("read_file"));

        assertFalse(AgentLoop.closeRetrieval(false, AgentLoop.RETRIEVAL_CALL_CAP - 1));
        assertTrue(AgentLoop.closeRetrieval(false, AgentLoop.RETRIEVAL_CALL_CAP));
        assertFalse(AgentLoop.closeRetrieval(true, AgentLoop.RETRIEVAL_CALL_CAP));
    }
}
