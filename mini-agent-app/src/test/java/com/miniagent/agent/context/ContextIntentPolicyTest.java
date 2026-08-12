package com.miniagent.agent.context;

import com.miniagent.agent.intent.IntentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContextIntentPolicyTest {

    @Test
    void questionLoadsNoHistoryNoTodo() {
        ContextIntentPolicy p = ContextIntentPolicy.forIntent(IntentType.QUESTION);
        assertEquals(0, p.historyMaxMessages());
        assertFalse(p.injectTodo());
        assertFalse(p.injectMidterm());
        assertFalse(p.injectMemory());
        assertFalse(p.suspendActiveTodo());
    }

    @Test
    void newTaskSuspendsOldTodoWithoutInjectingIt() {
        ContextIntentPolicy p = ContextIntentPolicy.forIntent(IntentType.NEW_TASK);
        assertTrue(p.suspendActiveTodo());
        assertFalse(p.injectTodo());
        assertEquals(6, p.historyMaxMessages());
    }

    @Test
    void continueResumesAndInjectsTodo() {
        ContextIntentPolicy p = ContextIntentPolicy.forIntent(IntentType.CONTINUE_TASK);
        assertTrue(p.resumeSuspendedTodo());
        assertTrue(p.injectTodo());
        assertEquals(-1, p.historyMaxMessages());
    }
}
