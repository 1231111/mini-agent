package com.miniagent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt-in end-to-end smoke test for the public agent question-answer HTTP API.
 *
 * <p>It talks directly to the running Spring endpoint; it does not start or
 * control the desktop client. Configure {@code MINI_AGENT_E2E_BASE_URL} and
 * either {@code MINI_AGENT_E2E_BEARER_TOKEN} or {@code MINI_AGENT_E2E_TOKEN_FILE}
 * to enable it. Optionally set {@code MINI_AGENT_E2E_SESSION_ID} to reuse a
 * particular conversation, plus {@code MINI_AGENT_E2E_PROMPT},
 * {@code MINI_AGENT_E2E_PERMISSION_MODE}, and {@code MINI_AGENT_E2E_CONFIRM_POLICY}.
 * Set {@code MINI_AGENT_E2E_TRACE_ONLY=true} when invoking the trace-report test;
 * otherwise that read-only test is skipped so a normal endpoint test only sends one task.
 * New conversations are retained so their trace can be inspected after the test.</p>
 */
class AgentQuestionAnswerEndpointTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    // Match the application's 30-minute async task deadline, with room for the
    // HTTP exchange to finish after the server has produced its terminal event.
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMinutes(31);
    private static final String DEFAULT_PROMPT = "请简短回答：2 + 2 等于几？不要调用工具。";

    @Test
    @Tag("agent-e2e")
    @Timeout(value = 32, unit = java.util.concurrent.TimeUnit.MINUTES)
    void sendsQuestionToAgentEndpointAndReceivesCompletedAnswer() throws Exception {
        String baseUrl = setting("MINI_AGENT_E2E_BASE_URL", "agent.e2e.baseUrl", null);
        String bearerToken = setting("MINI_AGENT_E2E_BEARER_TOKEN", "agent.e2e.bearerToken", null);
        String tokenFile = setting("MINI_AGENT_E2E_TOKEN_FILE", "agent.e2e.tokenFile", null);
        boolean registerEphemeralUser = Boolean.parseBoolean(setting(
                "MINI_AGENT_E2E_REGISTER_EPHEMERAL_USER", "agent.e2e.registerEphemeralUser", "false"));
        Assumptions.assumeTrue(isNotBlank(baseUrl)
                        && (isNotBlank(bearerToken) || isNotBlank(tokenFile) || registerEphemeralUser),
                "Set endpoint and bearer token/token file, or explicitly enable ephemeral test-user registration");

        String prompt = setting("MINI_AGENT_E2E_PROMPT", "agent.e2e.prompt", DEFAULT_PROMPT);
        String root = trimTrailingSlashes(baseUrl);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();

        if (!isNotBlank(bearerToken)) {
            if (isNotBlank(tokenFile)) {
                bearerToken = readBearerToken(tokenFile);
            } else {
                bearerToken = registerEphemeralUser(client, root);
            }
        }
        String authorization = bearerToken.regionMatches(true, 0, "Bearer ", 0, 7)
                ? bearerToken.trim() : "Bearer " + bearerToken.trim();

        String requestedSessionId = setting("MINI_AGENT_E2E_SESSION_ID", "agent.e2e.sessionId", null);
        String sessionId = isNotBlank(requestedSessionId)
                ? validateSessionId(requestedSessionId.trim())
                : createConversation(client, root, authorization);
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("message", prompt);
        String permissionMode = setting("MINI_AGENT_E2E_PERMISSION_MODE", "agent.e2e.permissionMode", null);
        String confirmPolicy = setting("MINI_AGENT_E2E_CONFIRM_POLICY", "agent.e2e.confirmPolicy", null);
        if (isNotBlank(permissionMode)) payload.put("permissionMode", permissionMode);
        if (isNotBlank(confirmPolicy)) payload.put("confirmPolicy", confirmPolicy);
        String requestBody = JSON.writeValueAsString(payload);
        HttpRequest chatRequest = HttpRequest.newBuilder(
                        URI.create(root + "/api/conversations/" + sessionId + "/messages/stream"))
                .timeout(RESPONSE_TIMEOUT)
                .header("Authorization", authorization)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = client.send(chatRequest,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode())
                .withFailMessage("Agent endpoint returned HTTP %s", response.statusCode())
                .isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("text/event-stream");

        List<SseEvent> events = parseSse(response.body());
        SseEvent outcomeEvent = lastEvent(events, "task_outcome");
        SseEvent endEvent = lastEvent(events, "end");
        SseEvent errorEvent = lastEvent(events, "error");

        assertThat(outcomeEvent)
                .withFailMessage("Agent stream did not include its task outcome event")
                .isNotNull();

        JsonNode outcome = JSON.readTree(outcomeEvent.data());
        String status = outcome.path("status").asText("").toUpperCase(Locale.ROOT);
        String reason = outcome.path("reason").asText("");
        System.out.printf("AGENT_E2E_RESULT sessionId=%s status=%s reason=%s%n", sessionId, status, reason);
        printLatestTrace(client, root, authorization, sessionId);
        assertThat(errorEvent)
                .withFailMessage("Agent SSE error (status=%s, reason=%s): %s",
                        status, reason, dataOf(errorEvent))
                .isNull();
        assertThat(status)
                .withFailMessage("Agent task ended with status %s (reason: %s)", status, reason)
                .isIn("SUCCESS", "COMPLETED");
        assertThat(endEvent)
                .withFailMessage("Agent stream did not include its terminal end event")
                .isNotNull();
        assertThat(endEvent.data()).isNotBlank();
    }

    @Test
    @Tag("agent-e2e")
    void reportsLatestTraceForConfiguredSession() throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(setting(
                        "MINI_AGENT_E2E_TRACE_ONLY", "agent.e2e.traceOnly", "false")),
                "Set MINI_AGENT_E2E_TRACE_ONLY=true to run the read-only trace report");
        String baseUrl = setting("MINI_AGENT_E2E_BASE_URL", "agent.e2e.baseUrl", null);
        String bearerToken = setting("MINI_AGENT_E2E_BEARER_TOKEN", "agent.e2e.bearerToken", null);
        String tokenFile = setting("MINI_AGENT_E2E_TOKEN_FILE", "agent.e2e.tokenFile", null);
        String sessionId = setting("MINI_AGENT_E2E_SESSION_ID", "agent.e2e.sessionId", null);
        Assumptions.assumeTrue(isNotBlank(baseUrl)
                        && (isNotBlank(bearerToken) || isNotBlank(tokenFile))
                        && isNotBlank(sessionId),
                "Set endpoint, token source, and MINI_AGENT_E2E_SESSION_ID to inspect a trace");
        if (!isNotBlank(bearerToken)) bearerToken = readBearerToken(tokenFile);

        String root = trimTrailingSlashes(baseUrl);
        String authorization = bearerToken.regionMatches(true, 0, "Bearer ", 0, 7)
                ? bearerToken.trim() : "Bearer " + bearerToken.trim();
        HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JsonNode executions = getJson(client, root + "/api/conversations/"
                + validateSessionId(sessionId.trim()) + "/executions", authorization);
        assertThat(executions.isArray() && !executions.isEmpty())
                .withFailMessage("No executions found for the configured session")
                .isTrue();

        JsonNode latest = executions.get(executions.size() - 1);
        String executionId = latest.path("executionId").asText("");
        assertThat(executionId).isNotBlank();
        System.out.printf("TRACE_SUMMARY executionId=%s status=%s steps=%s question=%s%n",
                executionId, latest.path("status").asText(""), latest.path("stepCount").asInt(),
                clip(latest.path("userQuestion").asText(""), 240));

        List<JsonNode> steps = readPagedItems(client, root + "/api/executions/"
                + executionId + "/steps", authorization);
        for (JsonNode step : steps) {
            String type = step.path("stepType").asText("");
            String status = step.path("status").asText("");
            String content = step.path("content").asText("");
            String toolName = step.path("toolName").asText("");
            String lower = content.toLowerCase(Locale.ROOT);
            boolean notable = status.equalsIgnoreCase("FAILURE")
                    || type.contains("ERROR") || type.contains("GOAL_COMPILED")
                    || type.contains("PLAN_VALIDATION") || type.contains("RECOVERY")
                    || (type.equals("TOOL_CALL")
                    && List.of("read_file", "exec_command", "todo").contains(toolName))
                    || lower.contains("plan_validation_failed") || lower.contains("referenceerror")
                    || lower.contains("syntaxerror") || lower.contains("unfinished")
                    || lower.contains("exit_code=1");
            if (notable) {
                System.out.printf("TRACE_STEP turn=%s type=%s tool=%s status=%s args=%s content=%s%n",
                        step.path("turnIndex").asInt(), type, toolName,
                        status, clip(step.path("toolArgs").asText(""), 400), clip(content, 400));
            }
        }

        List<JsonNode> decisions = readPagedItems(client, root + "/api/executions/"
                + executionId + "/decisions", authorization);
        for (JsonNode decision : decisions) {
            System.out.printf("TRACE_DECISION type=%s status=%s content=%s%n",
                    decision.path("stepType").asText(""), decision.path("status").asText(""),
                    clip(decision.path("content").asText(""), 900));
        }
    }

    private static String createConversation(HttpClient client, String root, String authorization)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(root + "/api/conversations"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode())
                .withFailMessage("Conversation endpoint returned HTTP %s", response.statusCode())
                .isEqualTo(200);

        JsonNode envelope = JSON.readTree(response.body());
        assertThat(envelope.path("success").asBoolean())
                .withFailMessage("Could not create test conversation (code=%s)",
                        envelope.path("code").asText(""))
                .isTrue();
        String sessionId = envelope.path("data").path("sessionId").asText("");
        assertThat(sessionId).startsWith("s_");
        return sessionId;
    }

    private static String registerEphemeralUser(HttpClient client, String root) throws Exception {
        String username = setting("MINI_AGENT_E2E_USERNAME", "agent.e2e.username",
                "codex-e2e-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        String password = setting("MINI_AGENT_E2E_PASSWORD", "agent.e2e.password",
                "E2e!" + java.util.UUID.randomUUID().toString().replace("-", "") + "a9");
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("password", password);
        payload.put("displayName", "API endpoint test");
        HttpRequest request = HttpRequest.newBuilder(URI.create(root + "/api/users"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode())
                .withFailMessage("Could not create ephemeral endpoint-test user (HTTP %s)", response.statusCode())
                .isEqualTo(200);
        JsonNode envelope = JSON.readTree(response.body());
        assertThat(envelope.path("success").asBoolean())
                .withFailMessage("Could not create ephemeral endpoint-test user (code=%s)",
                        envelope.path("code").asText(""))
                .isTrue();
        String token = envelope.path("data").path("token").asText("");
        assertThat(token).isNotBlank();
        return token;
    }

    private static JsonNode getJson(HttpClient client, String url, String authorization) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", authorization)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode envelope = JSON.readTree(response.body());
        assertThat(envelope.path("success").asBoolean())
                .withFailMessage("Trace API request was rejected (code=%s)", envelope.path("code").asText(""))
                .isTrue();
        return envelope.path("data");
    }

    private static void printLatestTrace(HttpClient client, String root, String authorization,
                                         String sessionId) throws Exception {
        JsonNode executions = getJson(client, root + "/api/conversations/"
                + validateSessionId(sessionId) + "/executions", authorization);
        if (!executions.isArray() || executions.isEmpty()) {
            System.out.printf("AGENT_E2E_TRACE sessionId=%s executions=0%n", sessionId);
            return;
        }
        JsonNode latest = executions.get(executions.size() - 1);
        String executionId = latest.path("executionId").asText("");
        System.out.printf("AGENT_E2E_TRACE executionId=%s status=%s stepCount=%d%n",
                executionId, latest.path("status").asText(""), latest.path("stepCount").asInt());
        if (executionId.isBlank()) return;
        List<JsonNode> steps = readPagedItems(client, root + "/api/executions/"
                + executionId + "/steps", authorization);
        for (JsonNode step : steps) {
            String type = step.path("stepType").asText("");
            String status = step.path("status").asText("");
            String content = step.path("content").asText("");
            if (status.equalsIgnoreCase("FAILURE") || type.contains("PLAN_VALIDATION")
                    || type.contains("GOAL_COMPILED") || type.equals("CODE_REVIEW")
                    || type.contains("HISTORY_PREFLIGHT") || type.equals("LLM_REQUEST_REJECTED")) {
                System.out.printf("AGENT_E2E_TRACE_STEP type=%s status=%s tool=%s content=%s%n",
                        type, status, step.path("toolName").asText(""), clip(content, 1800));
            }
        }
    }

    private static List<JsonNode> readPagedItems(HttpClient client, String baseUrl,
                                                  String authorization) throws Exception {
        List<JsonNode> items = new ArrayList<>();
        for (int page = 0; page < 10; page++) {
            JsonNode data = getJson(client, baseUrl + "?page=" + page + "&size=200", authorization);
            assertThat(data.isArray()).isTrue();
            data.forEach(items::add);
            if (data.size() < 200) break;
        }
        return items;
    }

    private static String readBearerToken(String tokenFile) throws Exception {
        JsonNode stored = JSON.readTree(Path.of(tokenFile).toFile());
        String token = stored.path("token").asText("").trim();
        assertThat(token).isNotBlank();
        return token;
    }

    private static String validateSessionId(String sessionId) {
        assertThat(sessionId).matches("s_[A-Za-z0-9_-]{1,98}");
        return sessionId;
    }

    private static List<SseEvent> parseSse(String body) {
        List<SseEvent> events = new ArrayList<>();
        String eventName = "";
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\\r?\\n", -1)) {
            if (line.isEmpty()) {
                appendEvent(events, eventName, data);
                eventName = "";
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) continue;
            int separator = line.indexOf(':');
            String field = separator < 0 ? line : line.substring(0, separator);
            String value = separator < 0 ? "" : line.substring(separator + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if ("event".equals(field)) {
                eventName = value;
            } else if ("data".equals(field)) {
                if (!data.isEmpty()) data.append('\n');
                data.append(value);
            }
        }
        appendEvent(events, eventName, data);
        return events;
    }

    private static void appendEvent(List<SseEvent> events, String eventName, StringBuilder data) {
        if (!eventName.isBlank() || !data.isEmpty()) {
            events.add(new SseEvent(eventName, data.toString()));
        }
    }

    private static SseEvent lastEvent(List<SseEvent> events, String eventName) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (eventName.equals(events.get(i).name())) return events.get(i);
        }
        return null;
    }

    private static String dataOf(SseEvent event) {
        return event == null ? "" : event.data();
    }

    private static String setting(String environmentName, String propertyName, String fallback) {
        String value = System.getProperty(propertyName);
        if (!isNotBlank(value)) value = System.getenv(environmentName);
        return isNotBlank(value) ? value.trim() : fallback;
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimTrailingSlashes(String value) {
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String clip(String value, int limit) {
        if (value == null || value.length() <= limit) return value == null ? "" : value;
        return value.substring(0, limit) + "...";
    }

    private record SseEvent(String name, String data) {}
}
