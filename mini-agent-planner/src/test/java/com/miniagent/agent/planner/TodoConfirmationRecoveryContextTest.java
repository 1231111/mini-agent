package com.miniagent.agent.planner;

import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.tool.ToolStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TodoConfirmationRecoveryContextTest {
    @TempDir Path directory;

    @Test
    void confirmationReleasesOnlyTheRequestedGateAndRetainsTheFailureForRecovery() {
        String diagnostic = "验收未通过：缺少关键词检索回执；先检索再只读命中区域";
        TaskNode accepted = node("accepted", TaskNodeStatus.SUCCESS).withOutput("已核验输入", Map.of());
        TaskNode waiting = node("analysis", TaskNodeStatus.AWAITING_CONFIRM)
                .withError(diagnostic).withRetryInc()
                .withOutput("已定位启动函数 QC()", Map.of("analysis", "已定位启动函数 QC()"))
                .withToolStatus(ToolStatus.FAILED);
        TaskNode anotherWait = node("other", TaskNodeStatus.AWAITING_CONFIRM).withError("等待另一个授权");
        TaskGraph graph = new TaskGraph(List.of(accepted, waiting, anotherWait));
        TaskTodoStore todos = new TaskTodoStore(directory.toString());
        TodoStateProjector projector = new TodoStateProjector(todos);

        TaskGraph confirmed = projector.confirmByTodoId(graph, 2);

        TaskNode resumed = confirmed.byId("analysis");
        assertThat(resumed.status()).isEqualTo(TaskNodeStatus.PENDING);
        assertThat(resumed.lastError()).isEqualTo(diagnostic);
        assertThat(resumed.retryCount()).isEqualTo(1);
        assertThat(resumed.output()).isEqualTo(waiting.output());
        assertThat(resumed.outputBindings()).isEqualTo(waiting.outputBindings());
        assertThat(resumed.doneWhen()).isEqualTo(waiting.doneWhen());
        assertThat(resumed.toolStatus()).isNull();
        assertThat(confirmed.byId("accepted")).isSameAs(accepted);
        assertThat(confirmed.byId("other")).isSameAs(anotherWait);
        assertThat(graph.byId("analysis")).isSameAs(waiting);

        projector.project("recovery-session", confirmed);
        assertThat(todos.get("recovery-session").get(1).note()).isEqualTo(diagnostic);
        assertThat(todos.get("recovery-session").get(1).evidence()).isEqualTo(waiting.output());
        assertThat(projector.confirmByTodoId(confirmed, 2)).isSameAs(confirmed);
    }

    @Test
    void invalidOrNonWaitingConfirmationCannotRewriteDiagnosticsOrOtherNodes() {
        TaskNode failed = node("failed", TaskNodeStatus.FAILED).withError("真实失败反馈");
        TaskGraph graph = new TaskGraph(List.of(failed));
        TodoStateProjector projector = new TodoStateProjector(new TaskTodoStore(directory.toString()));

        assertThat(projector.confirmByTodoId(graph, 1)).isSameAs(graph);
        assertThat(projector.confirmByTodoId(graph, 99)).isSameAs(graph);
        assertThat(projector.confirmByTodoId(graph, 0)).isSameAs(graph);
        assertThat(failed.lastError()).isEqualTo("真实失败反馈");
    }

    private static TaskNode node(String id, TaskNodeStatus status) {
        return new TaskNode(id, "分析原游戏结构", "file_read", List.of(), List.of(), List.of(),
                status, 80, DoneWhen.note(), "", "", 0, "");
    }
}
