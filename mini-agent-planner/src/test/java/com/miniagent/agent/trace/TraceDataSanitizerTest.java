package com.miniagent.agent.trace;

import com.miniagent.config.entity.AgentTraceStep;
import com.miniagent.config.repository.AgentTraceStepRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TraceDataSanitizerTest {
    @Test void redactsSensitiveJsonFieldsRecursivelyButKeepsOperationalData() {
        String sanitized = TraceDataSanitizer.sanitize("""
                {"url":"https://example.test/path","headers":{"Authorization":"Bearer abc.def.ghi"},
                 "api_key":"sk-12345678901234567890","body":{"password":"dont-store"},"token_count":42}
                """);

        assertTrue(sanitized.contains("https://example.test/path"));
        assertTrue(sanitized.contains("\"token_count\":42"));
        assertFalse(sanitized.contains("abc.def.ghi"));
        assertFalse(sanitized.contains("sk-12345678901234567890"));
        assertFalse(sanitized.contains("dont-store"));
        assertTrue(sanitized.contains("[REDACTED]"));
    }

    @Test void redactsInlineCredentialsWithoutChangingOrdinaryProse() {
        String sanitized = TraceDataSanitizer.sanitizeText(
                "Fetch https://alice:secret@example.test/a with Authorization: Bearer abc.def, api-key=sk-12345678901234567890; keep status=200");

        assertFalse(sanitized.contains("alice:secret"));
        assertFalse(sanitized.contains("abc.def"));
        assertFalse(sanitized.contains("sk-12345678901234567890"));
        assertTrue(sanitized.contains("Fetch https://"));
        assertTrue(sanitized.contains("keep status=200"));
    }

    @Test void recorderSanitizesPersistedQuestionAndToolArguments() {
        AgentTraceStepRepository repository = mock(AgentTraceStepRepository.class);
        when(repository.save(any(AgentTraceStep.class))).thenAnswer(invocation -> invocation.getArgument(0));
        TraceRecorder recorder = new TraceRecorder();
        ReflectionTestUtils.setField(recorder, "repo", repository);

        recorder.beginExecution("session-1", "exec-1", "My password=secret123 should stay private");
        recorder.recordToolCall("session-1", 1, "http_post",
                "{\"url\":\"https://example.test\",\"Authorization\":\"Bearer abc.def.ghi\"}");

        ArgumentCaptor<AgentTraceStep> saved = ArgumentCaptor.forClass(AgentTraceStep.class);
        verify(repository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertFalse(saved.getAllValues().get(0).getUserQuestion().contains("secret123"));
        assertFalse(saved.getAllValues().get(1).getToolArgs().contains("abc.def.ghi"));
        assertTrue(saved.getAllValues().get(1).getToolArgs().contains("[REDACTED]"));
    }
}
