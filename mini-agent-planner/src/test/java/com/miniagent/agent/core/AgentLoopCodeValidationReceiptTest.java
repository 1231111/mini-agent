package com.miniagent.agent.core;

import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.execution.AgentToolsProperties;
import com.miniagent.agent.execution.ToolExecutionGuards;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopCodeValidationReceiptTest {
    @TempDir Path workspace;

    @Test
    void onlySuccessfulMatchingTestToolResultsAreRecordedAndLaterEditsInvalidateThem() throws Exception {
        String session = "agent-loop-code-validation";
        TaskTodoStore store = new TaskTodoStore(workspace.resolve("todos").toString());
        store.set(session, List.of(Map.of("id", 1, "content", "修复 Java 航班解析 bug",
                "status", "in_progress", "done_when", "note_required")));
        AgentLoop loop = new AgentLoop();
        ReflectionTestUtils.setField(loop, "taskTodoStore", store);
        ReflectionTestUtils.setField(loop, "toolExecutionGuards", testGuards());
        Object state = newLoopState(session);

        invokeTrack(loop, state, "edit_file",
                "{\"path\":\"mini-agent-planner/src/main/java/com/miniagent/Foo.java\"}",
                "{\"success\":true,\"path\":\"mini-agent-planner/src/main/java/com/miniagent/Foo.java\"}",
                ToolResult.success("{\"success\":true}"));
        invokeTrack(loop, state, "exec_command", "{\"command\":\"git status\"}",
                "exit_code=0", ToolResult.success("exit_code=0"));
        invokeTrack(loop, state, "exec_command", "{\"command\":\"mvn -pl mini-agent-tools test\"}",
                "exit_code=0", ToolResult.success("exit_code=0"));
        assertTrue(store.hasPendingCodeValidation(session));

        invokeTrack(loop, state, "exec_command", "{\"command\":\"mvn -pl mini-agent-planner test\"}",
                "exit_code=0\nTests run: 8, Failures: 0", ToolResult.success("exit_code=0\nTests run: 8, Failures: 0"));
        assertTrue(store.isCodeValidationSatisfied(session));

        invokeTrack(loop, state, "edit_file",
                "{\"path\":\"mini-agent-planner/src/main/java/com/miniagent/Foo.java\"}",
                "{\"success\":true,\"path\":\"mini-agent-planner/src/main/java/com/miniagent/Foo.java\"}",
                ToolResult.success("{\"success\":true}"));
        assertTrue(store.hasPendingCodeValidation(session));

        invokeTrack(loop, state, "exec_command", "{\"command\":\"mvn -pl mini-agent-planner test\"}",
                "exit_code=0", ToolResult.success("exit_code=0"));
        assertTrue(store.isCodeValidationSatisfied(session));
        invokeTrack(loop, state, "exec_command", "{\"command\":\"python write_source.py\"}",
                "exit_code=1\npartial write", ToolResult.failure(
                        com.miniagent.agent.tool.ToolErrorCode.EXECUTION_FAILED, "exit_code=1", false,
                        "exit_code=1\npartial write"));
        assertTrue(store.hasPendingCodeValidation(session),
                "A failed shell write may have partially changed code and must invalidate the prior test receipt");
    }

    @Test
    void buildConfigWriteInLaterContinuationInvalidatesEarlierTestReceipt() throws Exception {
        String session = "agent-loop-config-validation";
        TaskTodoStore store = new TaskTodoStore(workspace.resolve("config-todos").toString());
        store.set(session, List.of(Map.of("id", 1, "content", "修复 Java 航班解析 bug",
                "status", "in_progress", "done_when", "note_required")));
        AgentLoop loop = new AgentLoop();
        ReflectionTestUtils.setField(loop, "taskTodoStore", store);
        ReflectionTestUtils.setField(loop, "toolExecutionGuards", testGuards());
        Object state = newLoopState(session);

        invokeTrack(loop, state, "exec_command", "{\"command\":\"mvn test\"}",
                "exit_code=0\nTests run: 5, Failures: 0", ToolResult.success("exit_code=0\nTests run: 5, Failures: 0"));
        assertTrue(store.isCodeValidationSatisfied(session));

        // A resumed turn may have a generic goal even though its code todo remains active.
        ReflectionTestUtils.setField(state, "codeChangeTask", false);
        invokeTrack(loop, state, "edit_file", "{\"path\":\"pom.xml\"}",
                "{\"success\":true}", ToolResult.success("{\"success\":true}"));

        assertTrue(store.hasPendingCodeValidation(session),
                "Changing build configuration in an active code task invalidates an earlier test receipt");
    }

    private static Object newLoopState(String sessionId) throws Exception {
        Class<?> type = Class.forName("com.miniagent.agent.core.AgentLoop$LoopState");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object state = constructor.newInstance();
        ReflectionTestUtils.setField(state, "sessionId", sessionId);
        ReflectionTestUtils.setField(state, "codeChangeTask", true);
        return state;
    }

    private static void invokeTrack(AgentLoop loop, Object state, String tool, String arguments,
                                    String result, ToolResult structured) {
        ReflectionTestUtils.invokeMethod(loop, "trackResult", tool, arguments, result, state, structured);
    }

    private static ToolExecutionGuards testGuards() {
        ToolRegistry registry = new ToolRegistry();
        registry.register("exec_command", "exec", Map.of("type", "object"), ignored -> "");
        return new ToolExecutionGuards(registry, new AgentToolsProperties());
    }
}
