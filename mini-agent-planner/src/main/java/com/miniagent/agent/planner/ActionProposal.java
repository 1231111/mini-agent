package com.miniagent.agent.planner;

import java.util.List;

public record ActionProposal(
        String proposalId,
        long basedOnVersion,
        String executionId,
        List<ActionSpec> actions
) {
    public ActionProposal {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }
}
