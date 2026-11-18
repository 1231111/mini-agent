package com.miniagent.agent.core;

import com.miniagent.agent.execution.ToolInvocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopFailureAccountingTest {

    @Test
    void canonicalArgumentsIgnoreObjectKeyOrderAndWhitespaceButPreserveArrayOrder() {
        assertEquals(AgentLoop.canonicalArguments("{\"a\":1,\"b\":{\"x\":2,\"y\":3}}"),
                AgentLoop.canonicalArguments("{ \"b\": { \"y\": 3, \"x\": 2 }, \"a\": 1 }"));
        assertFalse(AgentLoop.canonicalArguments("{\"items\":[1,2]}")
                .equals(AgentLoop.canonicalArguments("{\"items\":[2,1]}")));
    }

    @Test
    void onlyActuallyExecutedToolFailuresEnterTheRepeatedFailureCounter() {
        assertTrue(AgentLoop.countsTowardRepeatedExecutionFailure(ToolInvocation.Outcome.EXECUTED));
        assertFalse(AgentLoop.countsTowardRepeatedExecutionFailure(ToolInvocation.Outcome.GATE_DENIED));
        assertFalse(AgentLoop.countsTowardRepeatedExecutionFailure(ToolInvocation.Outcome.POLICY_DENIED));
        assertFalse(AgentLoop.countsTowardRepeatedExecutionFailure(ToolInvocation.Outcome.PERMISSION_DENIED));
        assertFalse(AgentLoop.countsTowardRepeatedExecutionFailure(ToolInvocation.Outcome.FENCE_REJECTED));
    }
}
