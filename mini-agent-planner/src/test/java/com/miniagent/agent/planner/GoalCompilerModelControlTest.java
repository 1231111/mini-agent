package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ModelCallControl;
import com.miniagent.agent.task.TaskPlan;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GoalCompilerModelControlTest {
    @Test
    @Timeout(10)
    void cancelBlockingCompileDoesNotRetryOrReturnDegradedGraph() throws Exception {
        assertCompilerCancellation(false);
    }

    @Test
    @Timeout(10)
    void cancelBlockingCorrectionDoesNotFallBackToFailedCompile() throws Exception {
        assertCompilerCancellation(true);
    }

    private void assertCompilerCancellation(boolean correction) throws Exception {
        ExecutionControl execution = new ExecutionControl(30_000, 100, 10_000);
        String session = correction ? "compiler-correction-cancel" : "compiler-cancel";
        execution.start(session);
        PlannerProperties properties = new PlannerProperties();
        properties.setCompilerRetry(3);
        GoalCompiler compiler = new GoalCompiler(properties);
        ReflectionTestUtils.setField(compiler, "modelCallControl", new ModelCallControl(execution));
        ChatModel model = mock(ChatModel.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return null;
        });
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = workers.submit(() -> {
                AgentLoop.setCurrentSession(session);
                try {
                    return correction
                            ? compiler.compileWithCorrection(model, "生成页面并验证", TaskPlan.of("生成页面并验证"), "补齐验收")
                            : compiler.compile(model, "生成页面并验证", TaskPlan.of("生成页面并验证"));
                } finally {
                    AgentLoop.clearCurrentSession();
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            execution.cancel(session);
            ExecutionException stopped = assertThrows(ExecutionException.class,
                    () -> result.get(2, TimeUnit.SECONDS));
            assertInstanceOf(ModelCallControl.StoppedException.class, stopped.getCause());
            assertEquals(ExecutionControl.StopReason.CANCELLED,
                    ((ModelCallControl.StoppedException) stopped.getCause()).reason());
            verify(model, times(1)).chat(any(ChatRequest.class));
        } finally {
            release.countDown();
            execution.finish(session);
        }
    }
}
