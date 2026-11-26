package com.miniagent.agent.core;

import com.miniagent.agent.execution.ToolInvocation;
import com.miniagent.agent.execution.ToolPipeline;
import com.miniagent.agent.execution.ToolRequest;
import com.miniagent.agent.execution.ToolExecutionGuards;
import com.miniagent.agent.hook.StopHookChain;
import com.miniagent.agent.permission.ConfirmPolicy;
import com.miniagent.agent.permission.PermissionContext;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.planner.ProposalTurnPolicy;
import com.miniagent.agent.surface.ToolSurface;
import com.miniagent.agent.tool.ToolDescriptor;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolSideEffect;
import com.miniagent.agent.todo.TaskTodoStore;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Exercises the real ReAct loop with API stubs; it never drives the desktop client. */
class AgentLoopNoProgressIntegrationTest {

    @AfterEach
    void clearThreadContexts() {
        AgentLoop.clearCurrentSession();
        LoopTurnContext.clear();
        PermissionContext.clear();
    }

    @Test
    void listFilesFailureReflectionUsesStructuredErrorWithoutSuggestingWorkspaceEscape() {
        AgentLoop loop = new AgentLoop();
        String args = "{\"path\":\"workspace/shared\"}";

        ToolResult missing = ToolResult.failure(ToolErrorCode.NOT_FOUND,
                "目录不存在: workspace/shared", false);
        String notFoundHint = ReflectionTestUtils.invokeMethod(loop, "buildReflectionHint",
                "list_files", args, missing.legacyText(), missing);
        assertThat(notFoundHint).contains("workspace/shared", "已经观察到的 workspace 清单")
                .doesNotContain("user.home", "exec_command", "web_search", "where ");

        ToolResult fileInsteadOfDirectory = ToolResult.failure(ToolErrorCode.INVALID_ARGUMENT,
                "目标不是目录: workspace/shared/server.js", false);
        String invalidHint = ReflectionTestUtils.invokeMethod(loop, "buildReflectionHint",
                "list_files", "{\"path\":\"workspace/shared/server.js\"}",
                fileInsteadOfDirectory.legacyText(), fileInsteadOfDirectory);
        assertThat(invalidHint).contains("read_file")
                .doesNotContain("user.home", "exec_command", "web_search", "where ");

        ToolResult denied = ToolResult.failure(ToolErrorCode.PERMISSION_DENIED,
                "目录访问被拒绝", false);
        String deniedHint = ReflectionTestUtils.invokeMethod(loop, "buildReflectionHint",
                "list_files", args, denied.legacyText(), denied);
        assertThat(deniedHint).contains("访问被拒绝", "授权范围")
                .doesNotContain("user.home", "exec_command", "web_search", "where ");
    }

    @Test
    void repeatedSuccessfulNoOpToolResultsStopBeforeGlobalToolQuota() {
        String sessionId = "s_no_progress_integration";
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolExecutions = new AtomicInteger();
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(ignored -> {
            int call = modelCalls.incrementAndGet();
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("call-" + call).name("exec_command")
                    .arguments("{\"command\":\"npm test\"}").build();
            ChatResponse response = mock(ChatResponse.class);
            when(response.aiMessage()).thenReturn(AiMessage.builder().text("")
                    .toolExecutionRequests(List.of(request)).build());
            return response;
        });

        ExecutionControl control = mock(ExecutionControl.class);
        when(control.afterModelTokens(anyString(), anyLong())).thenReturn(ExecutionControl.StopReason.NONE);
        when(control.afterModelTokens(anyString(), anyLong(), anyString(), anyLong(), anyLong()))
                .thenReturn(ExecutionControl.StopReason.NONE);
        when(control.checkpoint(anyString())).thenReturn(ExecutionControl.StopReason.NONE);
        when(control.stopReason(anyString())).thenReturn(ExecutionControl.StopReason.NONE);
        when(control.lastInputTokens(anyString())).thenReturn(0L);
        when(control.userId(anyString())).thenReturn(null);

        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolNames()).thenReturn(Set.of("exec_command"));
        when(registry.getSpecifications(any())).thenReturn(List.of());
        ToolSurface surface = mock(ToolSurface.class);
        when(surface.resolveTurn(any(), any(), anyBoolean()))
                .thenReturn(ToolSurface.Resolved.of(Set.of("exec_command")));
        ToolExecutionGuards guards = mock(ToolExecutionGuards.class);
        ToolDescriptor execDescriptor = new ToolDescriptor(
                "exec_command", "", Map.of(), ToolSideEffect.EXTERNAL_WRITE,
                false, false, false, com.miniagent.agent.tool.ToolExecutionProfile.DEFAULT);
        when(guards.descriptor(anyString())).thenReturn(execDescriptor);
        when(guards.descriptor(anyString(), any())).thenReturn(execDescriptor);
        ToolPipeline pipeline = mock(ToolPipeline.class);
        when(pipeline.invoke(any(ToolRequest.class))).thenAnswer(invocation -> {
            ToolRequest request = invocation.getArgument(0);
            toolExecutions.incrementAndGet();
            assertThat(request.scope().turnPolicy().denyToolInvocation(request.name(), request.arguments()))
                    .isNull();
            return ToolInvocation.executed(request.name(), ToolResult.success("exit_code=0\ntests: no tests found\n"));
        });
        TaskTodoStore todos = mock(TaskTodoStore.class);
        ContextCompressor compressor = mock(ContextCompressor.class);
        when(compressor.maybeCompress(anyList(), anyInt(), anyString(), anyInt()))
                .thenAnswer(invocation -> ContextCompressor.CompressResult.untouched(
                        invocation.getArgument(0), invocation.getArgument(3)));

        AgentLoop loop = new AgentLoop();
        ReflectionTestUtils.setField(loop, "toolRegistry", registry);
        ReflectionTestUtils.setField(loop, "toolSurface", surface);
        ReflectionTestUtils.setField(loop, "toolExecutionGuards", guards);
        ReflectionTestUtils.setField(loop, "toolPipeline", pipeline);
        ReflectionTestUtils.setField(loop, "executionControl", control);
        ReflectionTestUtils.setField(loop, "executionProperties", new ExecutionProperties());
        ReflectionTestUtils.setField(loop, "contextCompressor", compressor);
        ReflectionTestUtils.setField(loop, "streamingChatModel", mock(StreamingChatModel.class));
        ReflectionTestUtils.setField(loop, "taskTodoStore", todos);
        ReflectionTestUtils.setField(loop, "stopHookChain", new StopHookChain(List.of()));

        ProposalTurnPolicy policy = new ProposalTurnPolicy(List.of("exec_command"), true,
                "检查页面产物", Set.of(), 0, Set.of(), "npm test", new ProposalTurnPolicy.ProgressGuard());
        LoopTurnContext.set(policy);
        AgentLoop.setCurrentSession(sessionId);
        PermissionContext.force(sessionId, PermissionMode.ACCEPT_EDITS, ConfirmPolicy.DANGEROUS, true);

        AgentLoop.LoopOutcome outcome = loop.runOutcome(model, "system", "检查页面", List.of(), 90,
                null, null);

        assertThat(outcome.endReason()).isEqualTo(AgentLoop.NO_PROGRESS);
        assertThat(outcome.text()).contains("连续 3 次", "未产生可验证的新进展");
        assertThat(toolExecutions).hasValue(3);
        assertThat(modelCalls).hasValue(3);
    }

    @Test
    void exhaustedPlannerNodeBudgetStopsAsTypedQuotaOutcomeWithoutAnotherModelRetry() {
        String sessionId = "s_e4802c49c8c641628bf3fea87f6a6fce";
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger reservations = new AtomicInteger();
        AtomicInteger handlers = new AtomicInteger();
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenAnswer(ignored -> {
            int call = modelCalls.incrementAndGet();
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("budget-call-" + call).name("exec_command")
                    .arguments("{\"command\":\"npm test\"}").build();
            ChatResponse response = mock(ChatResponse.class);
            when(response.aiMessage()).thenReturn(AiMessage.builder().text("")
                    .toolExecutionRequests(List.of(request)).build());
            return response;
        });

        ExecutionControl control = mock(ExecutionControl.class);
        when(control.afterModelTokens(anyString(), anyLong())).thenReturn(ExecutionControl.StopReason.NONE);
        when(control.afterModelTokens(anyString(), anyLong(), anyString(), anyLong(), anyLong()))
                .thenReturn(ExecutionControl.StopReason.NONE);
        when(control.checkpoint(anyString())).thenReturn(ExecutionControl.StopReason.NONE);
        when(control.stopReason(anyString())).thenReturn(ExecutionControl.StopReason.NONE);
        when(control.lastInputTokens(anyString())).thenReturn(0L);
        when(control.userId(anyString())).thenReturn(null);

        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getToolNames()).thenReturn(Set.of("exec_command"));
        when(registry.getSpecifications(any())).thenReturn(List.of());
        ToolSurface surface = mock(ToolSurface.class);
        when(surface.resolveTurn(any(), any(), anyBoolean()))
                .thenReturn(ToolSurface.Resolved.of(Set.of("exec_command")));
        ToolExecutionGuards guards = mock(ToolExecutionGuards.class);
        ToolDescriptor execDescriptor = new ToolDescriptor(
                "exec_command", "", Map.of(), ToolSideEffect.EXTERNAL_WRITE,
                false, false, false, com.miniagent.agent.tool.ToolExecutionProfile.DEFAULT);
        when(guards.descriptor(anyString())).thenReturn(execDescriptor);
        when(guards.descriptor(anyString(), any())).thenReturn(execDescriptor);
        ToolPipeline pipeline = mock(ToolPipeline.class);
        when(pipeline.invoke(any(ToolRequest.class))).thenAnswer(invocation -> {
            ToolRequest request = invocation.getArgument(0);
            String denial = request.scope().turnPolicy().denyToolInvocation(
                    request.name(), request.arguments());
            if (denial != null) return ToolInvocation.gateDenied(
                    request.name(), ToolResult.fromLegacy(denial));
            handlers.incrementAndGet();
            return ToolInvocation.executed(request.name(), ToolResult.success("exit_code=0\ntests passed\n"));
        });
        TaskTodoStore todos = mock(TaskTodoStore.class);
        ContextCompressor compressor = mock(ContextCompressor.class);
        when(compressor.maybeCompress(anyList(), anyInt(), anyString(), anyInt()))
                .thenAnswer(invocation -> ContextCompressor.CompressResult.untouched(
                        invocation.getArgument(0), invocation.getArgument(3)));

        AgentLoop loop = new AgentLoop();
        ReflectionTestUtils.setField(loop, "toolRegistry", registry);
        ReflectionTestUtils.setField(loop, "toolSurface", surface);
        ReflectionTestUtils.setField(loop, "toolExecutionGuards", guards);
        ReflectionTestUtils.setField(loop, "toolPipeline", pipeline);
        ReflectionTestUtils.setField(loop, "executionControl", control);
        ReflectionTestUtils.setField(loop, "executionProperties", new ExecutionProperties());
        ReflectionTestUtils.setField(loop, "contextCompressor", compressor);
        ReflectionTestUtils.setField(loop, "streamingChatModel", mock(StreamingChatModel.class));
        ReflectionTestUtils.setField(loop, "taskTodoStore", todos);
        ReflectionTestUtils.setField(loop, "stopHookChain", new StopHookChain(List.of()));

        ProposalTurnPolicy.ProgressGuard guard = new ProposalTurnPolicy.ProgressGuard(() ->
                reservations.incrementAndGet() <= 2
                        ? ProposalTurnPolicy.ProgressGuard.BudgetDecision.allowed()
                        : ProposalTurnPolicy.ProgressGuard.BudgetDecision.denied(
                                LoopTurnPolicy.StopKind.TOOL_BUDGET_EXHAUSTED,
                                "节点「整理联机改造验收要求」已达到工具调用上限 12；继续前必须重规划"));
        LoopTurnContext.set(new ProposalTurnPolicy(List.of("exec_command"), true,
                "整理联机改造验收要求", Set.of(), 0, Set.of(), "npm test", guard));
        AgentLoop.setCurrentSession(sessionId);
        PermissionContext.force(sessionId, PermissionMode.ACCEPT_EDITS, ConfirmPolicy.DANGEROUS, true);

        AgentLoop.LoopOutcome outcome = loop.runOutcome(model, "system",
                "继续整理联机改造验收要求", List.of(), 90, null, null);

        assertThat(outcome.endReason()).isEqualTo(AgentLoop.RESOURCE_QUOTA_EXCEEDED);
        assertThat(outcome.controlStopReason()).isEqualTo(ExecutionControl.StopReason.TOOL_BUDGET_EXCEEDED);
        assertThat(outcome.unknownOutcome()).isFalse();
        assertThat(outcome.text()).contains("工具调用配额", "节点「整理联机改造验收要求」");
        assertThat(modelCalls).hasValue(3); // two reserved calls plus the denied request, no reflection/retry call
        assertThat(reservations).hasValue(3);
        assertThat(handlers).hasValue(2); // denied call never reaches the tool handler
    }
}
