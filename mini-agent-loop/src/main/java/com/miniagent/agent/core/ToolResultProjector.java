package com.miniagent.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.miniagent.agent.tool.ToolResult;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * 把成功工具结果变成用户可读文本。Loop 只问有没有可投影结果，不认厂商原文。
 */
public final class ToolResultProjector {

    static final String TOOL_WEB_SEARCH = "web_search";
    private static final int MAX_HITS = 5;

    private ToolResultProjector() {}

    public static Optional<String> project(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Optional.empty();
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (!(m instanceof ToolExecutionResultMessage tr)) {
                continue;
            }
            if (!TOOL_WEB_SEARCH.equals(tr.toolName()) || StringUtils.isBlank(tr.text())) {
                continue;
            }
            ToolResult parsed = ToolResult.fromLegacy(tr.text());
            if (!parsed.isSuccess()) {
                continue;
            }
            Optional<String> text = projectWebSearch(parsed);
            if (text.isPresent()) {
                return text;
            }
        }
        return Optional.empty();
    }

    static Optional<String> projectWebSearch(ToolResult result) {
        JsonNode web = result.data().path("data").path("web");
        if (!web.isArray() || web.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (JsonNode item : web) {
            if (n >= MAX_HITS) {
                break;
            }
            String title = item.path("title").asText("");
            String url = item.path("url").asText("");
            if (StringUtils.isBlank(title) && StringUtils.isBlank(url)) {
                continue;
            }
            n++;
            sb.append(n).append(". ");
            sb.append(StringUtils.isBlank(title) ? url : title);
            if (StringUtils.isNotBlank(url)) {
                sb.append('\n').append(url);
            }
            sb.append('\n');
        }
        if (n == 0) {
            return Optional.empty();
        }
        return Optional.of(sb.toString().trim());
    }
}
