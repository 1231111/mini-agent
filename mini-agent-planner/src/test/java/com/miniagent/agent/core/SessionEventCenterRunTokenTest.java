package com.miniagent.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.replica.ReplicaProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class SessionEventCenterRunTokenTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SessionEventCenter center = new SessionEventCenter();

    @AfterEach
    void close() {
        center.shutdown();
    }

    @Test
    void delayedProducerCannotWriteOrFinishTheNextExecution() {
        String oldRun = center.startRun("s", "first");
        String currentRun = center.startRun("s", "second");
        assertNotEquals(oldRun, currentRun);
        RecordingEmitter emitter = attach();

        center.publish("s", "token", "old text", oldRun);
        center.publish("s", "task_outcome", Map.of("status", "FAILURE"), oldRun);
        center.complete("s", "old final", oldRun);
        center.error("s", "old error", oldRun);

        assertTrue(center.hasRunningSession("s"));
        assertEquals(0, emitter.completions);
        assertEquals(List.of("session", "user"), emitter.names);
        center.publish("s", "token", "new text", currentRun);
        center.complete("s", "new final", currentRun);
        assertEquals(List.of("session", "user", "token", "end"), emitter.names);
        assertFalse(emitter.wire.toString().contains("old"));
        assertEquals(1, emitter.completions);
    }

    @Test
    void reconnectReplaysWaitingInteractionAndOutcomeBeforeEnd() {
        String run = center.startRun("s", "work");
        center.publish("s", "permission_ask", Map.of("tool", "exec_command", "grantToken", "grant"), run);
        center.publish("s", "task_outcome", Map.of("status", "WAITING", "reason", "PERM_ASK"), run);
        center.complete("s", "waiting", run);

        RecordingEmitter emitter = attach();
        assertEquals(List.of("session", "user", "permission_ask", "task_outcome", "end"), emitter.names);
        assertTrue(emitter.wire.toString().contains("WAITING"));
        assertTrue(emitter.wire.toString().contains("grantToken"));
        assertEquals(1, emitter.completions);
    }

    @Test
    void reconnectReplaysFailureOutcomeBeforeError() {
        String run = center.startRun("s", "work");
        center.publish("s", "task_outcome", Map.of("status", "FAILURE", "reason", "MAX_ITERATIONS"), run);
        center.error("s", "iteration budget exhausted", run);

        RecordingEmitter emitter = attach();
        assertEquals(List.of("session", "user", "task_outcome", "error"), emitter.names);
        assertTrue(emitter.wire.toString().contains("MAX_ITERATIONS"));
    }

    @Test
    void redisDelayedEventsAndTerminalFramesCannotAffectCurrentRun() throws Exception {
        receive("start", "user", "old work", "old");
        receive("event", "permission_ask", Map.of("tool", "exec_command"), "old");
        receive("event", "task_outcome", Map.of("status", "WAITING"), "old");
        receive("start", "user", "new work", "new");
        RecordingEmitter emitter = attach();
        receive("event", "token", "stale answer", "old");
        receive("event", "task_outcome", Map.of("status", "FAILURE"), "old");
        receive("complete", "end", "stale completion", "old");
        receive("error", "error", "stale error", "old");
        assertTrue(center.hasRunningSession("s"));
        assertEquals(List.of("session", "user"), emitter.names);
        assertEquals(0, emitter.completions);

        receive("event", "token", "current answer", "new");
        receive("event", "task_outcome", Map.of("status", "SUCCESS", "reason", "SUCCESS"), "new");
        receive("complete", "end", "current final", "new");
        assertFalse(center.hasRunningSession("s"));
        assertEquals(List.of("session", "user", "token", "task_outcome", "end"), emitter.names);
        assertFalse(emitter.wire.toString().contains("stale"));
        assertEquals(1, emitter.completions);
    }

    @Test
    void duplicateRedisStartDoesNotEraseBufferedCurrentAnswer() throws Exception {
        receive("start", "user", "work", "run");
        receive("event", "token", "preserved", "run");
        receive("start", "user", "work", "run");
        RecordingEmitter emitter = attach();
        assertEquals(List.of("session", "user", "token"), emitter.names);
        assertTrue(emitter.wire.toString().contains("preserved"));
    }

    @Test
    void legacyRedisEnvelopesWithoutTokenRemainSupported() throws Exception {
        receive("start", "user", "legacy work", null);
        receive("event", "token", "legacy answer", null);
        receive("complete", "end", "legacy final", null);
        RecordingEmitter emitter = attach();
        assertEquals(List.of("session", "user", "token", "end"), emitter.names);
        assertEquals(1, emitter.completions);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyRedisEnvelopeCarriesItsExecutionToken() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        ReplicaProperties properties = mock(ReplicaProperties.class);
        when(properties.isRedisMode()).thenReturn(true);
        ReflectionTestUtils.setField(center, "redis", redis);
        ReflectionTestUtils.setField(center, "replicaProperties", properties);
        List<JsonNode> published = new ArrayList<>();
        doAnswer(invocation -> {
            published.add(JSON.readTree(invocation.getArgument(1, String.class)));
            return null;
        }).when(redis).convertAndSend(anyString(), anyString());

        String first = center.startRun("s", "first");
        center.publish("s", "task_outcome", Map.of("status", "SUCCESS"), first);
        center.complete("s", "done", first);
        String second = center.startRun("s", "second");
        center.error("s", "failed", second);

        assertEquals(List.of("start", "event", "complete", "start", "error"),
                published.stream().map(node -> node.path("type").asText()).toList());
        assertEquals(List.of(first, first, first, second, second),
                published.stream().map(node -> node.path("runToken").asText()).toList());
        assertEquals("SUCCESS", published.get(1).path("data").path("status").asText());
    }

    private RecordingEmitter attach() {
        RecordingEmitter emitter = new RecordingEmitter();
        assertTrue(center.attachClient("s", 1L, emitter));
        return emitter;
    }

    private void receive(String type, String name, Object data, String runToken) throws Exception {
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("instanceId", "another-instance");
        envelope.put("sessionId", "s");
        envelope.put("type", type);
        envelope.put("name", name);
        envelope.put("data", data);
        if (runToken != null) envelope.put("runToken", runToken);
        Message message = mock(Message.class);
        when(message.getBody()).thenReturn(JSON.writeValueAsString(envelope).getBytes(StandardCharsets.UTF_8));
        ReflectionTestUtils.invokeMethod(center, "onRedisMessage", message, null);
    }

    private static final class RecordingEmitter extends SseEmitter {
        final List<String> names = new ArrayList<>();
        final StringBuilder wire = new StringBuilder();
        int completions;

        @Override
        public void send(SseEventBuilder event) {
            StringBuilder frame = new StringBuilder();
            event.build().forEach(part -> frame.append(part.getData()));
            var matcher = Pattern.compile("event:([^\\n]+)").matcher(frame);
            assertTrue(matcher.find(), () -> "Missing event name: " + frame);
            names.add(matcher.group(1));
            wire.append(frame);
        }

        @Override
        public void complete() {
            completions++;
        }
    }
}
