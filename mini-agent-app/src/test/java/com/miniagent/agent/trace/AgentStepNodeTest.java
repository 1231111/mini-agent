package com.miniagent.agent.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentStepNodeTest {

    @Test
    void coreBoundaryAndNewErrorNodes() {
        assertTrue(AgentStepNode.PLAN.isCore());
        assertFalse(AgentStepNode.SUB_GOAL.isCore());
        assertFalse(AgentStepNode.SUB_GOAL.isPersisted());
        assertFalse(AgentStepNode.TASK_LIST.isCore());
        assertFalse(AgentStepNode.REVIEW_PATH.isPersisted());
        assertTrue(AgentStepNode.TOOL_ERROR.isCore());
        assertTrue(AgentStepNode.AGENT_LOOP_END.isCore());
        assertTrue(AgentStepNode.WAITING_FOR_HUMAN.isCore());
        assertTrue(AgentStepNode.CANCELED.isCore());
        assertTrue(AgentStepNode.ABORTED.isCore());
        assertEquals("意图识别", AgentStepNode.INTENT_START.getGroup());
        assertEquals("意图识别", AgentStepNode.INTENT_L0.getGroup());
        assertEquals("意图识别", AgentStepNode.INTENT_END.getGroup());
        assertEquals("执行计划", AgentStepNode.TASK_PLAN.getGroup());
        assertEquals("上下文加载", AgentStepNode.CONTEXT_LOAD.getGroup());
        assertTrue(AgentStepNode.CONTEXT_LOAD.isCore());
        assertTrue(AgentStepNode.isKnownPersisted("CONTEXT_LOAD"));
        assertTrue(AgentStepNode.isKnownPersisted("INTENT_START"));
        assertTrue(AgentStepNode.isKnownPersisted("INTENT_END"));
        assertTrue(AgentStepNode.isKnownPersisted("GOAL_COMPILED"));
        assertTrue(AgentStepNode.isKnownPersisted("PROPOSAL"));
        assertTrue(AgentStepNode.isKnownPersisted("STATE_COMMIT"));
        assertTrue(AgentStepNode.isKnownPersisted("RECOVERY_REPLACE_TOOL"));
        assertEquals("规划控制", AgentStepNode.GOAL_COMPILED.getGroup());
        assertFalse(AgentStepNode.isKnownPersisted("REVIEW_PATH"));
        assertFalse(AgentStepNode.isCoreCode("TASK_LIST"));
        // 未知码：非核心，且不假装已登记
        assertFalse(AgentStepNode.isCoreCode("NOT_A_REAL_NODE"));
        assertFalse(AgentStepNode.isKnownPersisted("NOT_A_REAL_NODE"));
        // 历史库兼容
        assertTrue(AgentStepNode.ofCode("TODO_SET").isPresent());
        assertEquals(AgentStepNode.TASK_SET, AgentStepNode.ofCode("TODO_SET").orElseThrow());
        assertEquals(AgentStepNode.AGENT_LOOP_END, AgentStepNode.ofCode("LOOP_END").orElseThrow());
        assertTrue(AgentStepNode.values().length > 0);
        assertTrue(java.util.Arrays.stream(AgentStepNode.values())
                .noneMatch(n -> n.name().equals("LOOP_END")));
    }
}
