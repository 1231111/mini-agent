package com.miniagent.agent.core;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把成功的 web_search 收成标题和链接。长摘要被截断后 JSON 不完整，仍按键值抽取。
 */
public final class ToolResultProjector {

    static final String TOOL_WEB_SEARCH = "web_search";
    private static final int MAX_HITS = 5;
    private static final Pattern TITLE = Pattern.compile(
            "\"title\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern URL = Pattern.compile(
            "\"url\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");

    private ToolResultProjector() {}

    public static Optional<String> project(List<ChatMessage> messages) {
        List<ToolExecutionResultMessage> batch = trailingSearches(messages);
        if (batch.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (ToolExecutionResultMessage tr : batch) {
            for (String[] hit : extractHits(tr.text())) {
                n++;
                sb.append(n).append(". ");
                sb.append(StringUtils.isBlank(hit[0]) ? hit[1] : hit[0]);
                if (StringUtils.isNotBlank(hit[1])) {
                    sb.append('\n').append(hit[1]);
                }
                sb.append('\n');
            }
        }
        if (n == 0) {
            return Optional.empty();
        }
        return Optional.of(sb.toString().trim());
    }

    /** 丢掉摘要，只留标题和链接，避免原样重发触发安全网关的那份长文本。 */
    public static void compactTrailingSearches(List<ChatMessage> messages) {
        List<ToolExecutionResultMessage> batch = trailingSearches(messages);
        for (ToolExecutionResultMessage tr : batch) {
            String compact = compactJson(tr.text());
            if (compact == null) {
                continue;
            }
            int at = messages.indexOf(tr);
            if (at < 0) {
                continue;
            }
            messages.set(at, ToolExecutionResultMessage.from(
                    tr.id(), tr.toolName(), compact));
        }
    }

    static String compactJson(String raw) {
        List<String[]> hits = extractHits(raw);
        if (hits.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("{\"success\":true,\"data\":{\"web\":[");
        for (int i = 0; i < hits.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"title\":\"")
                    .append(escape(hits.get(i)[0]))
                    .append("\",\"url\":\"")
                    .append(escape(hits.get(i)[1]))
                    .append("\"}");
        }
        sb.append("]}}");
        return sb.toString();
    }

    static List<String[]> extractHits(String raw) {
        if (StringUtils.isBlank(raw) || raw.startsWith("{\"success\":false")) {
            return List.of();
        }
        Matcher titles = TITLE.matcher(raw);
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        List<String> titleText = new ArrayList<>();
        while (titles.find() && titleText.size() < MAX_HITS) {
            starts.add(titles.start());
            ends.add(titles.end());
            titleText.add(unescape(titles.group(1)));
        }
        List<String[]> hits = new ArrayList<>();
        for (int i = 0; i < titleText.size(); i++) {
            int from = ends.get(i);
            int next = i + 1 < starts.size() ? starts.get(i + 1) : raw.length();
            Matcher url = URL.matcher(raw);
            String link = "";
            if (url.find(from) && url.start() < next) {
                link = unescape(url.group(1));
            }
            if (StringUtils.isBlank(titleText.get(i)) && StringUtils.isBlank(link)) {
                continue;
            }
            hits.add(new String[] { titleText.get(i), link });
        }
        return hits;
    }

    private static List<ToolExecutionResultMessage> trailingSearches(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ToolExecutionResultMessage> found = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage m = messages.get(i);
            if (m instanceof SystemMessage) {
                continue;
            }
            if (!(m instanceof ToolExecutionResultMessage tr)
                    || !TOOL_WEB_SEARCH.equals(tr.toolName())) {
                break;
            }
            found.add(tr);
        }
        Collections.reverse(found);
        return found;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '"' || n == '\\' || n == '/') {
                    sb.append(n);
                    i++;
                    continue;
                }
                if (n == 'n') {
                    sb.append('\n');
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
