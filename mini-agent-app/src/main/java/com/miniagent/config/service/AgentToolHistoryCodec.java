package com.miniagent.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.common.SecurityUtils;
import com.miniagent.common.model.EffectiveModelContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Serializes only completed tool exchanges for durable conversation continuation. */
public final class AgentToolHistoryCodec {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_CALLS_PER_TURN = 16;
    private static final int MAX_ARGUMENT_CHARS = 4_000;
    private static final int MAX_RESULT_CHARS = 8_000;
    private static final int MAX_THINKING_CHARS = 8_000;
    private static final int MAX_HISTORY_JSON_CHARS = 96_000;

    private AgentToolHistoryCodec() {}

    /**
     * Encode complete assistant tool-call batches and their results after the latest
     * user message. Incomplete batches are omitted so a later turn can never replay
     * an action whose outcome was not durably observed.
     */
    public static String encodeLatestTurn(List<ChatMessage> messages) {
        return encodeLatestTurn(messages, EffectiveModelContext.currentSendThinking());
    }

    public static String encodeLatestTurn(List<ChatMessage> messages, boolean includeThinking) {
        if (messages == null || messages.isEmpty()) return null;
        int start = -1;
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof dev.langchain4j.data.message.UserMessage) start = i + 1;
        }
        if (start < 0) return null;
        return encodeFrom(messages, start, includeThinking);
    }

    /** Encode an already isolated list of completed exchanges, such as a DAG turn's node transcripts. */
    public static String encodeExchanges(List<ChatMessage> messages) {
        return encodeExchanges(messages, EffectiveModelContext.currentSendThinking());
    }

    public static String encodeExchanges(List<ChatMessage> messages, boolean includeThinking) {
        return messages == null || messages.isEmpty() ? null : encodeFrom(messages, 0, includeThinking);
    }

    private static String encodeFrom(List<ChatMessage> messages, int start, boolean includeThinking) {
        ArrayNode encoded = JSON.createArrayNode();
        int callsSaved = 0;
        for (int i = start; i < messages.size() && callsSaved < MAX_CALLS_PER_TURN; i++) {
            if (!(messages.get(i) instanceof AiMessage ai) || !ai.hasToolExecutionRequests()) continue;
            List<ToolExecutionRequest> calls = ai.toolExecutionRequests();
            if (calls == null || calls.isEmpty() || calls.size() > MAX_CALLS_PER_TURN - callsSaved) continue;

            Map<String, ToolExecutionRequest> byId = new HashMap<>();
            boolean valid = true;
            for (ToolExecutionRequest call : calls) {
                if (call == null || blank(call.id()) || blank(call.name())
                        || byId.putIfAbsent(call.id(), call) != null) {
                    valid = false;
                    break;
                }
            }
            if (!valid) continue;

            Map<String, ToolExecutionResultMessage> matched = new HashMap<>();
            List<ToolExecutionResultMessage> orderedResults = new ArrayList<>();
            for (int j = i + 1; j < messages.size(); j++) {
                ChatMessage next = messages.get(j);
                if (next instanceof AiMessage nextAi && nextAi.hasToolExecutionRequests()) break;
                if (!(next instanceof ToolExecutionResultMessage result)
                        || !byId.containsKey(result.id())) continue;
                ToolExecutionRequest request = byId.get(result.id());
                if (!request.name().equals(result.toolName())
                        || matched.putIfAbsent(result.id(), result) != null) {
                    valid = false;
                    break;
                }
                orderedResults.add(result);
            }
            if (!valid || matched.size() != calls.size()) continue;

            ObjectNode assistant = JSON.createObjectNode();
            assistant.put("type", "assistant_tool_calls");
            assistant.put("text", safe(ai.text(), 1_200));
            // DeepSeek requires the opaque reasoning_content from an assistant tool-call
            // message to accompany its replay. Persist it only for providers configured to
            // require it, with the same redaction and strict size bound as other stored text.
            if (includeThinking && !blank(ai.thinking())) {
                assistant.put("thinking", safe(ai.thinking(), MAX_THINKING_CHARS));
            }
            ArrayNode callArray = assistant.putArray("calls");
            for (ToolExecutionRequest call : calls) {
                ObjectNode item = callArray.addObject();
                item.put("id", call.id());
                item.put("name", call.name());
                item.put("arguments", safe(call.arguments(), MAX_ARGUMENT_CHARS));
            }
            ArrayNode resultArray = assistant.putArray("results");
            for (ToolExecutionResultMessage result : orderedResults) {
                ObjectNode item = resultArray.addObject();
                item.put("id", result.id());
                item.put("name", result.toolName());
                item.put("text", safe(result.text(), MAX_RESULT_CHARS));
            }
            encoded.add(assistant);
            callsSaved += calls.size();
            while (i + 1 < messages.size()
                    && messages.get(i + 1) instanceof ToolExecutionResultMessage) i++;
        }
        while (!encoded.isEmpty()) {
            try {
                String value = JSON.writeValueAsString(encoded);
                if (value.length() <= MAX_HISTORY_JSON_CHARS) return value;
            } catch (Exception e) {
                return null;
            }
            // Retain the most recent complete exchanges if a turn hit the storage cap.
            encoded.remove(0);
        }
        return null;
    }

    /** Rebuild only well-formed, fully paired tool exchanges from stored JSON. */
    public static List<ChatMessage> decode(String value) {
        if (blank(value) || value.length() > MAX_HISTORY_JSON_CHARS) return List.of();
        try {
            JsonNode root = JSON.readTree(value);
            if (root == null || !root.isArray()) return List.of();
            List<ChatMessage> messages = new ArrayList<>();
            int callCount = 0;
            for (JsonNode batch : root) {
                if (!"assistant_tool_calls".equals(text(batch, "type"))) continue;
                JsonNode callNodes = batch.get("calls");
                JsonNode resultNodes = batch.get("results");
                if (callNodes == null || !callNodes.isArray() || callNodes.isEmpty()
                        || resultNodes == null || !resultNodes.isArray()
                        || callNodes.size() != resultNodes.size()
                        || callNodes.size() > MAX_CALLS_PER_TURN - callCount) continue;

                List<ToolExecutionRequest> calls = new ArrayList<>();
                Map<String, String> names = new HashMap<>();
                Set<String> batchIds = new HashSet<>();
                boolean valid = true;
                for (JsonNode call : callNodes) {
                    String id = text(call, "id");
                    String name = text(call, "name");
                    if (blank(id) || blank(name) || !batchIds.add(id)) {
                        valid = false;
                        break;
                    }
                    names.put(id, name);
                    calls.add(ToolExecutionRequest.builder().id(id).name(name)
                            .arguments(text(call, "arguments")).build());
                }
                List<ToolExecutionResultMessage> results = new ArrayList<>();
                Set<String> resultIds = new HashSet<>();
                if (valid) {
                    for (JsonNode result : resultNodes) {
                        String id = text(result, "id");
                        String name = text(result, "name");
                        if (blank(id) || !names.containsKey(id) || !names.get(id).equals(name)
                                || !resultIds.add(id)) {
                            valid = false;
                            break;
                        }
                        results.add(ToolExecutionResultMessage.from(id, name, text(result, "text")));
                    }
                }
                if (!valid || resultIds.size() != calls.size()) continue;
                messages.add(AiMessage.builder().text(text(batch, "text"))
                        .thinking(text(batch, "thinking"))
                        .toolExecutionRequests(calls).build());
                messages.addAll(results);
                callCount += calls.size();
            }
            return List.copyOf(messages);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static String safe(String value, int maxChars) {
        String redacted = SecurityUtils.redactSensitive(value == null ? "" : value);
        return redacted.length() <= maxChars ? redacted : redacted.substring(0, maxChars) + "…";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
