package com.miniagent.agent.hook;

import com.miniagent.agent.permission.PermissionMode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanUnapprovedStopHookTest {
    private final PlanUnapprovedStopHook hook = new PlanUnapprovedStopHook();

    @Test
    void nudgesAreBoundedAndExhaustionReturnsAnExplicitNonSuccessDecision() {
        StopDecision first = hook.evaluate(context(0));
        StopDecision last = hook.evaluate(context(PlanUnapprovedStopHook.MAX_NUDGES));

        assertEquals(StopDecision.Action.BLOCK_RETRY, first.action());
        assertEquals(StopDecision.Action.PREVENT_CONTINUATION, last.action());
        assertEquals("plan_not_approved", last.reason());
        org.junit.jupiter.api.Assertions.assertTrue(last.message().contains("为避免误报完成"));
    }

    private static StopContext context(int nudges) {
        return new StopContext("s", "done", 0, 8, Set.of("write_file"), false, false,
                false, PermissionMode.PLAN, false, false, false, false, 0, nudges, "");
    }
}
