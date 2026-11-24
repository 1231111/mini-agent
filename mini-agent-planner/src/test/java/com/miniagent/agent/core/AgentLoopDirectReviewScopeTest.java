package com.miniagent.agent.core;

import com.miniagent.agent.execution.ToolExecutionGuards;
import com.miniagent.agent.execution.ToolRequest;
import com.miniagent.agent.planner.CodeDiffReviewService;
import com.miniagent.agent.tool.ToolDescriptor;
import com.miniagent.agent.tool.ToolExecutionProfile;
import com.miniagent.agent.tool.ToolSideEffect;
import com.miniagent.agent.tool.WorkspaceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentLoopDirectReviewScopeTest {

    @TempDir
    Path tempDirectory;

    @AfterEach
    void clearThreadContexts() {
        AgentLoop.clearCurrentSession();
        LoopTurnContext.clear();
        WorkspaceContext.clearAll();
    }

    @Test
    void directCodeWritesExpandReviewBaselineOnlyToTheirDeclaredPaths() throws Exception {
        Path workspace = Files.createDirectory(tempDirectory.resolve("direct-review-scope"));
        Files.writeString(workspace.resolve("page.html"), "<main>before</main>\n");
        Files.writeString(workspace.resolve("behavior.js"), "export const ready = false;\n");
        // A whole-workspace baseline would exceed the review snapshot limit. The
        // direct route must capture only the actual file targets before mutation.
        Files.writeString(workspace.resolve("unrelated.js"), "x".repeat(700_000));
        WorkspaceContext.setRootOverride(workspace);
        String sessionId = "direct-review-scope-session";
        AgentLoop.setCurrentSession(sessionId);

        CodeDiffReviewService service = new CodeDiffReviewService(null, null);
        AgentLoop loop = new AgentLoop();
        ReflectionTestUtils.setField(loop, "codeDiffReviewService", service);
        Object state = newLoopState();
        ReflectionTestUtils.setField(state, "sessionId", sessionId);

        armFileWrite(loop, state, "write_file", "page.html");
        CodeDiffReviewService.Baseline first = (CodeDiffReviewService.Baseline)
                ReflectionTestUtils.getField(state, "codeReviewBaseline");
        assertThat(first).isNotNull();
        assertThat(first.available()).isTrue();
        assertThat(first.workspaceScopePaths()).containsExactly("page.html");
        assertThat(first.initialContents()).containsOnlyKeys("page.html");

        Files.writeString(workspace.resolve("page.html"), "<main>after</main>\n");
        armFileWrite(loop, state, "edit_file", "behavior.js");
        CodeDiffReviewService.Baseline expanded = (CodeDiffReviewService.Baseline)
                ReflectionTestUtils.getField(state, "codeReviewBaseline");

        assertThat(expanded.available()).isTrue();
        assertThat(expanded.workspaceScopePaths()).containsExactlyInAnyOrder("page.html", "behavior.js");
        assertThat(expanded.initialContents()).containsEntry("page.html", "<main>before</main>\n")
                .containsEntry("behavior.js", "export const ready = false;\n")
                .doesNotContainKey("unrelated.js");
    }

    @Test
    void directMutatingShellIsDeniedEvenWithScopedBaselineUntilOsIsolationExists() throws Exception {
        AgentLoop loop = new AgentLoop();
        ToolExecutionGuards guards = mock(ToolExecutionGuards.class);
        ReflectionTestUtils.setField(loop, "toolExecutionGuards", guards);
        ToolDescriptor mutating = descriptor(ToolSideEffect.EXTERNAL_WRITE, false);
        ToolDescriptor readOnly = descriptor(ToolSideEffect.READ_ONLY, true);
        when(guards.descriptor(anyString(), any())).thenAnswer(invocation ->
                invocation.getArgument(1, String.class).contains("git status") ? readOnly : mutating);
        Object state = newLoopState();
        ReflectionTestUtils.setField(state, "allowedToolsForTurn", Set.of("exec_command"));
        ReflectionTestUtils.setField(state, "codeReviewBaseline",
                new CodeDiffReviewService.Baseline(tempDirectory, "", Map.of("page.html", "before"), Set.of(),
                        "", CodeDiffReviewService.BaselineKind.WORKSPACE_SNAPSHOT, Set.of("page.html")));
        RunScope scope = RunScope.capture();

        ToolRequest writeCommand = toolRequest(loop, "exec_command", "{\"command\":\"npm run build\"}", state, scope);
        ToolRequest readCommand = toolRequest(loop, "exec_command", "{\"command\":\"git status\"}", state, scope);

        assertThat(writeCommand.probeDeny()).contains("暂不执行写入型 shell 命令", "OS 级文件访问隔离");
        assertThat(readCommand.probeDeny()).isNull();
    }

    private static ToolDescriptor descriptor(ToolSideEffect sideEffect, boolean idempotent) {
        return new ToolDescriptor("exec_command", "", Map.of(), sideEffect,
                idempotent, false, false, ToolExecutionProfile.DEFAULT);
    }

    private static Object newLoopState() throws Exception {
        Class<?> type = Class.forName("com.miniagent.agent.core.AgentLoop$LoopState");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void armFileWrite(AgentLoop loop, Object state, String tool, String path) throws Exception {
        Method method = AgentLoop.class.getDeclaredMethod("armCodeReviewForFileWrite",
                String.class, String.class, state.getClass());
        method.setAccessible(true);
        method.invoke(loop, tool, "{\"path\":\"" + path + "\"}", state);
    }

    private static ToolRequest toolRequest(AgentLoop loop, String tool, String arguments,
                                          Object state, RunScope scope) throws Exception {
        Method method = AgentLoop.class.getDeclaredMethod("toolRequest", String.class, String.class,
                int.class, boolean.class, state.getClass(), RunScope.class);
        method.setAccessible(true);
        return (ToolRequest) method.invoke(loop, tool, arguments, 0, false, state, scope);
    }
}
