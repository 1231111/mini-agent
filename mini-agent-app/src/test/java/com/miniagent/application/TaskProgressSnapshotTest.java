package com.miniagent.application;

import com.miniagent.agent.todo.TaskTodoStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskProgressSnapshotTest {

    @Test
    void capturesSummaryAndCurrentNodeOnlyForMatchingExecution() {
        TaskTodoStore.TodoItem node = new TaskTodoStore.TodoItem(2, "正在检查页面",
                TaskTodoStore.Status.in_progress, "", "", "", "", List.of(1), 1, 2);
        TaskTodoStore.ProgressContext context = new TaskTodoStore.ProgressContext("exec-current", 12,
                "生成并验证页面", "2", Map.of("executionId", "exec-current", "version", 12L,
                "nodeId", "2", "message", "执行器已开始此步骤"));

        TaskProgressSnapshot current = TaskProgressSnapshot.capture("exec-current", 9, "RUNNING",
                "", List.of(node), context);
        TaskProgressSnapshot stale = TaskProgressSnapshot.capture("exec-new", 9, "SUCCESS",
                "", List.of(node), context);

        assertThat(current.summary()).isEqualTo("生成并验证页面");
        assertThat(current.version()).isEqualTo(12);
        assertThat(current.currentNodeId()).isEqualTo("2");
        assertThat(current.items()).hasSize(1);
        assertThat(stale.executionId()).isEqualTo("exec-new");
        assertThat(stale.items()).isEmpty();
        assertThat(stale.summary()).isEmpty();
        assertThat(stale.currentProgress()).isEmpty();
    }

    @Test
    void legacySnapshotsRemainReadableWithEmptyNewMetadata() {
        TaskProgressSnapshot legacy = TaskProgressSnapshot.fromJson("""
                {"executionId":"exec-legacy","taskId":4,"status":"SUCCESS","reason":"",
                 "items":[{"id":1,"content":"完成","status":"completed","note":"","dependsOn":[]}]}
                """);

        assertThat(legacy).isNotNull();
        assertThat(legacy.items()).hasSize(1);
        assertThat(legacy.summary()).isEmpty();
        assertThat(legacy.version()).isZero();
        assertThat(legacy.currentNodeId()).isEmpty();
        assertThat(legacy.currentProgress()).isEmpty();
    }
}
