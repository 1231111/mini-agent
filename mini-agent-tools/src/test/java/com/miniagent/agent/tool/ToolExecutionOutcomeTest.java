package com.miniagent.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionOutcomeTest {
    @Test
    void writeExceptionAfterCommitIsUnknownForBothHandlerContracts() {
        for (ToolSideEffect effect : new ToolSideEffect[]{ToolSideEffect.WRITE, ToolSideEffect.EXTERNAL_WRITE}) {
            AtomicInteger writes = new AtomicInteger();
            Tool legacy = Tool.builder().name("write").sideEffect(effect).handler(args -> {
                writes.incrementAndGet();
                throw new IllegalStateException("connection reset after commit");
            }).build();
            Tool structured = Tool.builder().name("write").sideEffect(effect).resultHandler(args -> {
                writes.incrementAndGet();
                throw new IllegalStateException("connection reset after commit");
            }).build();

            assertUnknown(legacy.executeResult("{}"));
            assertUnknown(structured.executeResult("{}"));
            assertEquals(2, writes.get());
        }
    }

    @Test
    void missingAcknowledgementAfterWriteIsUnknown() {
        for (String raw : new String[]{null, "", "  "}) {
            Tool legacy = Tool.builder().name("write").handler(args -> raw).build();
            assertUnknown(legacy.executeResult("{}"));
        }
        Tool structured = Tool.builder().name("write").resultHandler(args -> null).build();
        assertUnknown(structured.executeResult("{}"));
    }

    @Test
    void readonlyExceptionAndEmptyResultRemainConfirmedFailures() {
        Tool failing = Tool.builder().name("read").sideEffect(ToolSideEffect.READ_ONLY)
                .handler(args -> { throw new IllegalStateException("connection reset"); }).build();
        Tool empty = Tool.builder().name("read").sideEffect(ToolSideEffect.READ_ONLY)
                .resultHandler(args -> null).build();
        assertEquals(ToolStatus.FAILED, failing.executeResult("{}").status());
        assertEquals(ToolErrorCode.EMPTY_RESULT, empty.executeResult("{}").errorCode());
    }

    @Test
    void readonlyExecUsesActualCommandRatherThanConservativeRegistrationContract() {
        Tool exec = Tool.builder().name("exec_command").sideEffect(ToolSideEffect.EXTERNAL_WRITE)
                .handler(args -> { throw new IllegalStateException("connection reset"); }).build();
        assertEquals(ToolStatus.FAILED, exec.executeResult("{\"command\":\"git status\"}").status());
        assertUnknown(exec.executeResult("{\"command\":\"git push origin master\"}"));
    }

    @Test
    void invalidArgumentsAndMissingExecutorDoNotImplySideEffects() {
        AtomicInteger calls = new AtomicInteger();
        Tool tool = Tool.builder().name("write").parameters(Map.of("target", Map.of(
                "type", "string", "required", true))).handler(args -> {
            calls.incrementAndGet();
            return "ok";
        }).build();
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, tool.executeResult("{}").errorCode());
        assertEquals(0, calls.get());
        assertEquals(ToolStatus.FAILED, Tool.builder().name("unconfigured").build().executeResult("{}").status());
    }

    @Test
    void explicitStructuredAndLegacyFailuresRetainTheirContracts() {
        ToolResult rejected = ToolResult.failure(ToolErrorCode.INVALID_ARGUMENT, "rejected before commit", false);
        Tool structured = Tool.builder().name("write").resultHandler(args -> rejected).build();
        Tool legacy = Tool.builder().name("write").handler(args -> rejected.legacyText()).build();
        assertSame(rejected, structured.executeResult("{}"));
        assertEquals(ToolErrorCode.INVALID_ARGUMENT, legacy.executeResult("{}").errorCode());
        assertEquals(ToolStatus.FAILED, legacy.executeResult("{}").status());
    }

    @Test
    void diagnosticMessagesRemainValidAcrossLegacyJsonRoundTrip() {
        String message = "failed at C:\\workspace\\out.txt\nserver replied:\t\"connection reset\"\r\n";
        ToolResult unknown = ToolResult.unknown(message, null);
        ToolResult failure = ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, message, false);

        assertEquals(ToolStatus.UNKNOWN, ToolResult.fromLegacy(unknown.legacyText()).status());
        assertEquals(message, ToolResult.fromLegacy(unknown.legacyText()).message());
        assertEquals(ToolStatus.FAILED, ToolResult.fromLegacy(failure.legacyText()).status());
        assertEquals(message, ToolResult.fromLegacy(failure.legacyText()).message());
        assertTrue(unknown.data().isObject());
        assertTrue(failure.data().isObject());
    }

    private static void assertUnknown(ToolResult result) {
        assertEquals(ToolStatus.UNKNOWN, result.status(), result::toString);
        assertEquals(ToolErrorCode.OUTCOME_UNKNOWN, result.errorCode());
        assertFalse(result.retriable());
    }
}
