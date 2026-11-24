package com.miniagent.agent.planner;

import com.miniagent.agent.todo.TaskTodoStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TodoProgressProjectionTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void projectsVersionSummaryCurrentNodeAndTrustedExecutorPhaseTogether() {
        TaskTodoStore todos = new TaskTodoStore(temporaryDirectory.toString());
        TodoStateProjector projector = new TodoStateProjector(todos);
        TaskNode completed = node("source", "读取照片", List.of(), TaskNodeStatus.SUCCESS);
        TaskNode running = node("classify", "按地点分类照片", List.of("source"), TaskNodeStatus.RUNNING);
        Goal goal = new Goal("goal-1", "读取照片并按地点分类，生成相册", "", null, List.of(), List.of());
        StateSnapshot snapshot = new StateSnapshot(27, "session#81", "exec-81", goal,
                new TaskGraph(List.of(completed, running)), Map.of(), Map.of(), List.of(), 0);

        projector.project("session#81", snapshot);

        TaskTodoStore.ProgressContext context = todos.progressContext("session#81");
        assertThat(context).isNotNull();
        assertThat(context.executionId()).isEqualTo("exec-81");
        assertThat(context.version()).isEqualTo(27);
        assertThat(context.summary()).isEqualTo("读取照片并按地点分类，生成相册");
        assertThat(context.currentNodeId()).isEqualTo("2");
        assertThat(context.currentProgress()).containsEntry("executionId", "exec-81")
                .containsEntry("version", 27L).containsEntry("nodeId", "2")
                .containsEntry("message", "执行器已开始此步骤")
                .doesNotContainKeys("completed", "total");

        List<TaskTodoStore.TodoItem> items = todos.get("session#81");
        assertThat(items).hasSize(2);
        assertThat(items.get(1).dependsOn()).containsExactly(1);
        assertThat(items.get(1).status()).isEqualTo(TaskTodoStore.Status.in_progress);
    }

    @Test
    void progressSummaryIsBoundedAndWhitespaceNormalized() {
        String longObjective = "步骤 ".repeat(100);
        StateSnapshot snapshot = new StateSnapshot(3, "session#82", "exec-82",
                new Goal("goal-2", longObjective, "", null, List.of(), List.of()),
                new TaskGraph(List.of(node("n1", "阶段一", List.of(), TaskNodeStatus.READY))),
                Map.of(), Map.of(), List.of(), 0);
        TaskTodoStore todos = new TaskTodoStore(temporaryDirectory.toString());

        new TodoStateProjector(todos).project("session#82", snapshot);

        assertThat(todos.progressContext("session#82").summary()).hasSizeLessThanOrEqualTo(180);
        assertThat(todos.progressContext("session#82").summary()).endsWith("…");
    }

    private static TaskNode node(String id, String name, List<String> dependencies, TaskNodeStatus status) {
        return new TaskNode(id, name, "file_read", dependencies, List.of(), List.of(), status,
                1, DoneWhen.note(), "", "", 0, "evidence");
    }
}
