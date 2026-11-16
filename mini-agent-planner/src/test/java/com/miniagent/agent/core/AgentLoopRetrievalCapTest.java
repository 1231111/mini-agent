package com.miniagent.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopRetrievalCapTest {

    @Test
    void retrievalToolsAreRecognized() {
        assertTrue(AgentLoop.isRetrievalTool("web_search"));
        assertTrue(AgentLoop.isRetrievalTool("web_extract"));
        assertFalse(AgentLoop.isRetrievalTool("browser_navigate"));
        assertFalse(AgentLoop.isRetrievalTool("browser_click"));
        assertFalse(AgentLoop.isRetrievalTool("browser_screenshot"));
        assertFalse(AgentLoop.isRetrievalTool("desktop_act"));
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

    @Test
    void originalPageExtractionClosesAtItsOwnCap() {
        assertFalse(AgentLoop.closeWebExtract(0));
        assertFalse(AgentLoop.closeWebExtract(AgentLoop.WEB_EXTRACT_CALL_CAP - 1));
        assertTrue(AgentLoop.closeWebExtract(AgentLoop.WEB_EXTRACT_CALL_CAP));
        assertTrue(AgentLoop.closeWebExtract(AgentLoop.WEB_EXTRACT_CALL_CAP + 1));
        assertFalse(AgentLoop.closeWebExtract(10, 0));
    }

    @Test
    void sameBatchCannotExceedOriginalPageExtractionCap() {
        var capped = AgentLoop.capWebExtractCalls(List.of("web_extract", "web_search", "web_extract",
                "web_extract", "web_extract"), 0, 3, value -> value);
        assertEquals(List.of("web_extract", "web_search", "web_extract", "web_extract"), capped.allowed());
        assertEquals(List.of("web_extract"), capped.skipped());

        var remaining = AgentLoop.capWebExtractCalls(List.of("web_extract", "web_extract"), 2, 3, value -> value);
        assertEquals(List.of("web_extract"), remaining.allowed());
        assertEquals(List.of("web_extract"), remaining.skipped());
    }
}
