package com.miniagent.agent.core;

import com.miniagent.common.MessageConstants;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
                AiMessage.from(
                        "The request was rejected because it was considered high risk"),
                ToolExecutionResultMessage.from("id1", "web_search", json)));
        assertTrue(out.isPresent());
        assertTrue(out.get().contains("Gartner"));
        assertTrue(out.get().contains("https://www.gartner.com/cn/a"));
        assertFalse(out.get().contains("high risk"));
        assertFalse(out.get().contains("description"));
    }

    @Test
    void truncatedSearchStillProjectsBothQueries() {
        String full = "{\"success\":true,\"data\":{\"web\":["
                + "{\"title\":\"完整条目\",\"url\":\"https://a.example/1\",\"description\":\""
                + "x".repeat(9000)
                + "\"}]}}";
        String cut = full.substring(0, 8000)
                + "\n... （结果过长已截断，共 " + full.length() + " 字符）";
        String second = "{\"success\":true,\"data\":{\"web\":["
                + "{\"title\":\"第二条\",\"url\":\"https://b.example/2\"}]}}";
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ToolExecutionResultMessage.from("id1", "web_search", cut));
        msgs.add(ToolExecutionResultMessage.from("id2", "web_search", second));
        msgs.add(SystemMessage.from("hint"));
        Optional<String> out = ToolResultProjector.project(msgs);
        assertTrue(out.isPresent());
        assertTrue(out.get().contains("完整条目"));
        assertTrue(out.get().contains("https://a.example/1"));
        assertTrue(out.get().contains("第二条"));
        assertFalse(out.get().contains("description"));
        ToolResultProjector.compactTrailingSearches(msgs);
        String compact = ((ToolExecutionResultMessage) msgs.get(0)).text();
        assertTrue(compact.contains("完整条目"));
        assertFalse(compact.contains("xxxxx"));
        assertTrue(ToolResultProjector.project(msgs).get().contains("https://b.example/2"));
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
