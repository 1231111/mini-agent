package com.miniagent.agent.core;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopToolNameSanitizationTest {

    @Test
    void stripsOnlyKnownXmlSuffixWhenCanonicalNameIsAdvertised() {
        Set<String> allowed = Set.of("todo", "write_file");

        assertEquals("todo", AgentLoop.canonicalToolName("todo</parameter", allowed));
        assertEquals("todo", AgentLoop.canonicalToolName("todo</parameter>", allowed));
        assertEquals("write_file", AgentLoop.canonicalToolName("write_file</function>", allowed));
    }

    @Test
    void doesNotFuzzyMatchUnknownOrUnadvertisedNames() {
        Set<String> allowed = Set.of("todo");

        assertEquals("todo-extra", AgentLoop.canonicalToolName("todo-extra", allowed));
        assertEquals("todo</xml>", AgentLoop.canonicalToolName("todo</xml>", allowed));
        assertEquals("todo</parameter>", AgentLoop.canonicalToolName("todo</parameter>", Set.of("write_file")));
    }

    @Test
    void normalizedRequestKeepsCallIdAndArguments() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1").name("todo</parameter").arguments("{\"action\":\"submit\"}").build();

        List<?> result = AgentLoop.normalizeToolCalls(List.of(request), Set.of("todo"));

        assertEquals(1, result.size());
        assertInstanceOf(ToolExecutionRequest.class, result.getFirst());
        ToolExecutionRequest normalized = (ToolExecutionRequest) result.getFirst();
        assertEquals("call-1", normalized.id());
        assertEquals("todo", normalized.name());
        assertEquals("{\"action\":\"submit\"}", normalized.arguments());
    }
}
