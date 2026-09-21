package com.miniagent.agent.hook;

import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskSignals;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.MessageConstants;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoPlanStopHookTest {

    private final TodoPlanStopHook hook = new TodoPlanStopHook();

    @Test
    void plannerOwnedDoesNotDemandTodoSet() {
        StopDecision d = hook.evaluate(ctx(false, true, false, false, 0, 0));
        assertTrue(d.isProceed());
    }

    @Test
    void directMissingPlanBlocks() {
        StopDecision d = hook.evaluate(ctx(true, false, false, false, 0, 0));
        assertEquals(StopDecision.Action.BLOCK_RETRY, d.action());
        assertEquals(ErrorCode.TODO_STOP_MISSING_PLAN.getCode(), d.reason());
        assertEquals(MessageConstants.STOP_NEED_TODO_PLAN, d.message());
    }

    @Test
    void directMissingPlanGivesUpAfterNudges() {
        StopDecision d = hook.evaluate(ctx(true, false, false, false,
                TodoPlanStopHook.MAX_NUDGES, 0));
        assertTrue(d.isProceed());
    }

    @Test
    void directIncompleteBlocks() {
        StopDecision d = hook.evaluate(ctx(false, false, true, true, 0, 0));
        assertEquals(StopDecision.Action.BLOCK_RETRY, d.action());
        assertEquals(ErrorCode.TODO_STOP_INCOMPLETE.getCode(), d.reason());
    }

    @Test
    void lightQaIgnoresLeftoverTodos() {
        StopContext c = new StopContext(
                "s", "你好", 0, 8, Set.of(), false, false, false, false,
                PermissionMode.DEFAULT, true, true, false, true, true, 0, 0, "todo");
        assertTrue(hook.evaluate(c).isProceed());
    }

    @Test
    void graphOwnedToolGuidanceSkipsDirectPlanningDoc() {
        String owned = com.miniagent.agent.context.ContextContributorConfiguration
                .toolGuidance(Set.of("todo", "write_file"), true);
        String direct = com.miniagent.agent.context.ContextContributorConfiguration
                .toolGuidance(Set.of("todo", "write_file"), false);
        assertTrue(!owned.contains("仅当当前会话还没有 todo 清单"));
        assertTrue(direct.contains("仅当当前会话还没有 todo 清单"));
    }

    @Test
    void graphOwnedPromptDoesNotAskTodoSet() {
        TaskPlan plan = TaskPlan.of("写报告", true, TaskSignals.parse("file"));
        String owned = plan.toPromptBlock(true);
        String direct = plan.toPromptBlock(false);
        assertTrue(owned.contains(MessageConstants.TASK_GRAPH_OWNED));
        assertTrue(!owned.contains("仅清单为空时才"));
        assertTrue(direct.contains("仅清单为空时才"));
    }

    private static StopContext ctx(boolean structured, boolean plannerOwned,
                                   boolean hasPlan, boolean runnable,
                                   int missingNudges, int incompleteNudges) {
        return new StopContext(
                "s", "done", 0, 8, Set.of(), false, false, structured, false,
                PermissionMode.DEFAULT, true, false, plannerOwned, hasPlan, runnable,
                missingNudges, incompleteNudges, "list");
    }
}
