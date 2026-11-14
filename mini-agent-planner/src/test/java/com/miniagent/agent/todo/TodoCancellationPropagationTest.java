package com.miniagent.agent.todo;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ModelCallControl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TodoCancellationPropagationTest {
    @Test
    void cancelledAcceptanceDoesNotBecomeValidationFailureOrCompleteTodo(@TempDir Path directory) {
        TaskTodoStore store = cancelledValidatorStore(directory);
        String[] validationError = {null};
        assertThrows(ModelCallControl.StoppedException.class,
                () -> store.update("cancelled-todo", 1, "completed", "", "校验实际结果", validationError));
        assertNull(validationError[0]);
        assertEquals(TaskTodoStore.Status.in_progress, store.get("cancelled-todo").getFirst().status());
    }

    @Test
    void todoToolDoesNotTurnCancellationIntoErrorJson(@TempDir Path directory) {
        TaskTodoStore store = cancelledValidatorStore(directory);
        TodoTool tool = new TodoTool();
        ReflectionTestUtils.setField(tool, "todoStore", store);
        TaskTodoContext.set("cancelled-todo");
        try {
            assertThrows(ModelCallControl.StoppedException.class, () ->
                    ReflectionTestUtils.invokeMethod(tool, "handle",
                            "{\"action\":\"update\",\"id\":1,\"status\":\"completed\",\"evidence\":\"校验实际结果\"}"));
            assertEquals(TaskTodoStore.Status.in_progress, store.get("cancelled-todo").getFirst().status());
        } finally {
            TaskTodoContext.clear();
        }
    }

    private static TaskTodoStore cancelledValidatorStore(Path directory) {
        TodoStepValidator validator = new TodoStepValidator() {
            @Override public String name() { return "cancelled-judge"; }
            @Override public String validate(TaskTodoStore.TodoItem item, String evidence) {
                throw new ModelCallControl.StoppedException(ExecutionControl.StopReason.CANCELLED);
            }
        };
        TaskTodoStore store = new TaskTodoStore(directory.toString(), List.of(validator));
        store.set("cancelled-todo", List.of(Map.of("id", 1, "content", "核验网页实际结果",
                "status", "in_progress", "done_when", "note_required")));
        return store;
    }
}
