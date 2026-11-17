package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProposalTurnPolicySubmitTest {
    @Test void evidenceSubmissionHasTheSameFocusBoundaryAsUpdates() {
        ProposalTurnPolicy policy = new ProposalTurnPolicy(List.of("todo"), true, "current", Set.of(2));
        assertNull(policy.denyTodoArgs("{\"action\":\"submit\",\"id\":2,\"evidence\":\"actual receipt\"}"));
        assertNotNull(policy.denyTodoArgs("{\"action\":\"submit\",\"id\":1,\"evidence\":\"other task\"}"));
        assertNotNull(policy.denyTodoArgs("{\"action\":\"submit\",\"evidence\":\"missing target\"}"));
        assertNull(policy.denyTodoArgs("{\"action\":\"update\",\"id\":2,\"status\":\"awaiting_confirm\"}"));
        assertNotNull(policy.denyTodoArgs("{\"action\":\"set\",\"items\":[]}"));
        assertNotNull(policy.denyTodoArgs("{\"action\":\"clear\"}"));
    }

    @Test void anUnboundProposalCannotSubmitEvidenceForAnyNode() {
        ProposalTurnPolicy policy = new ProposalTurnPolicy(List.of("todo"), true, "unbound", Set.of());
        assertNotNull(policy.denyTodoArgs("{\"action\":\"submit\",\"id\":1,\"evidence\":\"receipt\"}"));
        assertNull(policy.denyTodoArgs("{\"action\":\"list\"}"));
    }
}
