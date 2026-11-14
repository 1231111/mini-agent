package com.miniagent.agent.todo;

import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ModelCallControl;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
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

class LlmJudgeModelControlTest {
    @Test
    @Timeout(10)
    void cancellationIsControlStopRatherThanFailedAcceptance() throws Exception {
        ExecutionControl execution = new ExecutionControl(30_000, 100, 10_000);
        String session = "judge-model-cancel";
        execution.start(session);
        LlmJudgeTodoValidator validator = new LlmJudgeTodoValidator();
        ReflectionTestUtils.setField(validator, "modelCallControl", new ModelCallControl(execution));
        ChatModel model = mock(ChatModel.class);
        ReflectionTestUtils.setField(validator, "chatModel", model);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(model.chat(org.mockito.ArgumentMatchers.<dev.langchain4j.data.message.ChatMessage>anyList())).thenAnswer(invocation -> {
            entered.countDown();
            release.await();
            return null;
        });
        TaskTodoStore.TodoItem item = new TaskTodoStore.TodoItem(1, "验证网页", TaskTodoStore.Status.pending,
                "", "llm_judge:页面是否包含所有字段", "页面包含姓名、年龄、地址和保存按钮");
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = workers.submit(() -> {
                AgentLoop.setCurrentSession(session);
                try {
                    return validator.validate(item, item.evidence());
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
            verify(model, times(1)).chat(org.mockito.ArgumentMatchers.<dev.langchain4j.data.message.ChatMessage>anyList());
        } finally {
            release.countDown();
            execution.finish(session);
        }
    }
}
