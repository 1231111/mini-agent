package com.miniagent.agent.memory.lifecycle;

import com.miniagent.config.OpenAiModelProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DefaultConsolidationServiceTest {

    @Test
    void splitFacts忽略空段和无() {
        assertEquals(List.of(), DefaultConsolidationService.splitFacts(null));
        assertEquals(List.of(), DefaultConsolidationService.splitFacts("  "));
        assertEquals(List.of(), DefaultConsolidationService.splitFacts("无"));
        assertEquals(List.of("用 Java 21", "输出 Markdown"),
                DefaultConsolidationService.splitFacts("用 Java 21; 输出 Markdown ;无"));
    }

    @Test
    void keyHintMasksApiKey() {
        assertEquals("missing", OpenAiModelProperties.keyHint(null));
        assertEquals("not-configured", OpenAiModelProperties.keyHint("not-configured"));
        assertEquals("sk-1...wxyz", OpenAiModelProperties.keyHint("sk-1234567890wxyz"));
    }
}
