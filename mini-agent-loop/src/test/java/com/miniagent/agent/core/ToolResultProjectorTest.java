package com.miniagent.agent.core;

import com.miniagent.common.MessageConstants;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolResultProjectorTest {

    @Test
    void projectsWebSearchTitleAndUrl() {
        String json = "{\"success\":true,\"data\":{\"web\":["
                + "{\"title\":\"Gartner发布2026中国人工智能十大趋势\","
                + "\"url\":\"https://www.gartner.com/cn/a\"},"
                + "{\"title\":\"世界AI新闻快讯\",\"url\":\"https://example.com/b\"}]}}";
        Optional<String> out = ToolResultProjector.project(List.of(
                ToolExecutionResultMessage.from("id1", "web_search", json),
                AiMessage.from(
                        "The request was rejected because it was considered high risk")));
        assertTrue(out.isPresent());
        assertTrue(out.get().contains("Gartner"));
        assertTrue(out.get().contains("https://www.gartner.com/cn/a"));
        assertFalse(out.get().contains("high risk"));
        assertFalse(out.get().contains("description"));
    }

    @Test
    void failedSearchIsNotProjectable() {
        Optional<String> out = ToolResultProjector.project(List.of(
                ToolExecutionResultMessage.from("id1", "web_search",
                        "{\"success\":false,\"error\":\"搜索失败\"}")));
        assertTrue(out.isEmpty());
    }

    @Test
    void refusedWithoutToolsUsesBusinessCopy() {
        assertEquals("模型未能给出可用答复，请换个说法再试。",
                MessageConstants.AGENT_LLM_REFUSED);
        assertTrue(ToolResultProjector.project(List.of(
                AiMessage.from("The request was rejected because it was considered high risk")))
                .isEmpty());
    }
}
