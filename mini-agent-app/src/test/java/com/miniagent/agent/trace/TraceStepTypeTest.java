package com.miniagent.agent.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TraceStepTypeTest {

    @Test
    void persistedCodesCoverRuntimeWriters() {
        // 运行时会写入的码必须都在枚举且 persisted=true
        String[] must = {
                "RUN_START", "RUN_END",
                "INTENT_L0", "INTENT_L1", "INTENT_L2", "TASK_PLAN", "REVIEW_PATH",
                "AGENT_LOOP_START", "SUBAGENT_LOOP_START", "SUBAGENT_LOOP_END",
                "TODO_SEED", "TODO_SET", "TODO_UPDATE", "TODO_LIST", "TODO_REOPEN", "TODO_CONFIRM", "TODO_CLEAR",
                "PLAN", "SUB_GOAL", "THINKING", "DECISION", "LLM_CALL",
                "TOOL_CALL", "TOOL_RESULT", "PERM_DENY", "HOOK_DENY",
                "COMPRESSION", "ANSWER", "LOOP_END", "ERROR"
        };
        for (String code : must) {
            assertTrue(TraceStepType.isKnownPersisted(code), "missing persisted: " + code);
        }
        assertFalse(TraceStepType.isKnownPersisted("LLM_REQUEST"));
        assertFalse(TraceStepType.isKnownPersisted("LLM_RESPONSE"));
        assertFalse(TraceStepType.isKnownPersisted("NOT_A_NODE"));
        assertEquals(must.length + 2, TraceStepType.values().length);
    }
}
