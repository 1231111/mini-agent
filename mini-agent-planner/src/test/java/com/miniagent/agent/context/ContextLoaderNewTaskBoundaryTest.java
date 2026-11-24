package com.miniagent.agent.context;

import com.miniagent.agent.scope.TaskBoundary;
import com.miniagent.agent.scope.TaskScopeRegistry;
import com.miniagent.agent.planner.TodoStateProjector;
import com.miniagent.agent.todo.TaskTodoStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextLoaderNewTaskBoundaryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void newSemanticTaskGetsAnEmptyScopeAndLeavesUnfinishedTaskResumable() {
        String sessionId = "s_new_task_boundary";
        TaskScopeRegistry scopes = new TaskScopeRegistry();
        TaskTodoStore todos = new TaskTodoStore(temporaryDirectory.toString());
        todos.setScopeRegistry(scopes);
        Map<String, Object> oldNode = new LinkedHashMap<>();
        oldNode.put("id", 1);
        oldNode.put("content", "验证五步页面结构完整性");
        oldNode.put("status", "in_progress");
        todos.overwrite(sessionId, List.of(oldNode));
        TodoStateProjector projector = new TodoStateProjector(todos);
        assertThat(projector.currentTaskContext(sessionId)).contains("验证五步页面结构完整性");

        ContextBuilder contextBuilder = mock(ContextBuilder.class);
        when(contextBuilder.buildDetailed(any(ContextBuildContext.class)))
                .thenReturn(new ContextBuildResult("fresh task context", List.of()));
        ContextLoader loader = new ContextLoader();
        ReflectionTestUtils.setField(loader, "taskTodoStore", todos);
        ReflectionTestUtils.setField(loader, "taskScopeRegistry", scopes);
        ReflectionTestUtils.setField(loader, "contextBuilder", contextBuilder);

        LoadedContext loaded = loader.load(sessionId, "分析汽车电子 Tier1 企业战略", false,
                List.of(), TaskBoundary.NEW);

        assertThat(loaded.taskId()).isEqualTo(1);
        assertThat(loaded.scopeKey()).isEqualTo(sessionId + "#1");
        assertThat(todos.get(sessionId)).isEmpty();
        assertThat(projector.resumableTaskContext(sessionId))
                .contains("taskId=0")
                .contains("验证五步页面结构完整性");

        LoadedContext resumed = loader.load(sessionId, "继续上一个未完成任务", false,
                List.of(), TaskBoundary.RESUME);
        assertThat(resumed.taskId()).isZero();
        assertThat(resumed.scopeKey()).isEqualTo(sessionId);
        assertThat(todos.get(sessionId)).extracting(TaskTodoStore.TodoItem::content)
                .containsExactly("验证五步页面结构完整性");
        assertThat(todos.get(sessionId)).extracting(TaskTodoStore.TodoItem::status)
                .containsExactly(TaskTodoStore.Status.in_progress);
    }
}
