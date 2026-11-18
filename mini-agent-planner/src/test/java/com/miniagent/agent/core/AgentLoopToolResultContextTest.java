package com.miniagent.agent.core;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopToolResultContextTest {

    @Test
    @SuppressWarnings("unchecked")
    void repeatedReadOnlyToolCallReturnsNextCachedResultChunk() throws Exception {
        String key = "read_file:{\"path\":\"large.txt\"}";
        String fullResult = "x".repeat(25_001);
        Class<?> stateType = Class.forName("com.miniagent.agent.core.AgentLoop$LoopState");
        Constructor<?> constructor = stateType.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object state = constructor.newInstance();
        Map<String, String> cache = (Map<String, String>) ReflectionTestUtils.getField(state, "toolResultCache");
        cache.put(key, fullResult);
        AgentLoop loop = new AgentLoop();

        String first = ReflectionTestUtils.invokeMethod(loop, "clampForContext",
                fullResult, "read_file", key, state);
        String second = ReflectionTestUtils.invokeMethod(loop, "clampForContext",
                fullResult, "read_file", key, state);
        String third = ReflectionTestUtils.invokeMethod(loop, "clampForContext",
                fullResult, "read_file", key, state);

        assertTrue(first.contains("chunk 1-10000/25001"));
        assertTrue(first.endsWith(fullResult.substring(0, 10_000)));
        assertTrue(second.contains("chunk 10001-20000/25001"));
        assertTrue(second.endsWith(fullResult.substring(10_000, 20_000)));
        assertTrue(third.endsWith(fullResult.substring(20_000)));
    }
}
