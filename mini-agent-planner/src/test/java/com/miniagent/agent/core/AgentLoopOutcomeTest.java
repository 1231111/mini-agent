package com.miniagent.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopOutcomeTest {
    @Test
    void onlyExplicitSuccessWithAnAnswerIsSuccessful() {
        assertTrue(outcome("answer", "SUCCESS").successful());
        assertFalse(outcome(" ", "SUCCESS").successful());
        assertFalse(outcome("partial answer", "MAX_ITERATIONS").successful());
        assertFalse(outcome("partial answer", "INCOMPLETE_TODO").successful());
        assertFalse(outcome("partial answer", null).successful());
    }

    @Test
    void waitingAndCancellationRemainDistinctFromCompletion() {
        assertTrue(outcome("question", AgentLoop.USER_QUESTION).awaitingHuman());
        assertTrue(outcome("approval", AgentLoop.PERM_ASK).awaitingHuman());
        assertFalse(outcome("answer", "SUCCESS").awaitingHuman());
        assertTrue(outcome("stopped", AgentLoop.CANCELLED).cancelled());
        assertTrue(outcome("stopped", "CANCELED").cancelled());
        assertFalse(outcome("partial", AgentLoop.DEADLINE_EXCEEDED).cancelled());
    }

    private static AgentLoop.LoopOutcome outcome(String text, String reason) {
        return new AgentLoop.LoopOutcome(text, reason, List.of(), List.of(), null);
    }
}
