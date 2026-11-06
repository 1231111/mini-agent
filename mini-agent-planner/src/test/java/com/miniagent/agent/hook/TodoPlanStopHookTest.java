package com.miniagent.agent.hook;

import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.common.MessageConstants;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoPlanStopHookTest {

    private final TodoPlanStopHook hook = new TodoPlanStopHook();

    @Test
    void plannerOwnedDoesNotDemandTodoSet() {
        StopDecision d = hook.evaluate(ctx(true, false, false, 0));
        assertTrue(d.isProceed());
    }

    @Test
    void directIncompleteBlocks() {
        StopDecision d = hook.evaluate(ctx(false, true, true, 0));
        assertEquals(StopDecision.Action.BLOCK_RETRY, d.action());
        assertEquals(com.miniagent.common.ErrorCode.TODO_STOP_INCOMPLETE.getCode(), d.reason());
    }

    @Test
    void directIncompleteGivesUpAfterNudges() {
        StopDecision d = hook.evaluate(ctx(false, true, true, TodoPlanStopHook.MAX_NUDGES));
        assertTrue(d.isProceed());
    }

    /** 模型不建计划就收尾，不再被拦：是否拆解由模型自己决定。 */
    @Test
    void missingPlanIsNoLongerBlocked() {
        assertTrue(hook.evaluate(ctx(false, false, false, 0)).isProceed());
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
        TaskPlan plan = TaskPlan.of("写报告");
        String owned = plan.toPromptBlock(true);
        String direct = plan.toPromptBlock(false);
        assertTrue(owned.contains(MessageConstants.TASK_GRAPH_OWNED));
        assertTrue(direct.contains("直接执行用户目标"));
        assertTrue(!owned.contains("仅清单为空时才"));
        assertTrue(!direct.contains("仅清单为空时才"));
    }

    private static StopContext ctx(boolean plannerOwned, boolean hasPlan,
                                   boolean runnable, int incompleteNudges) {
        return new StopContext(
                "s", "done", 0, 8, Set.of(), false, false, false,
                PermissionMode.DEFAULT, true, plannerOwned, hasPlan, runnable,
                incompleteNudges, "list");
    }
}
