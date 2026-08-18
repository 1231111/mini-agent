package com.miniagent.agent.planner;

import java.util.List;

public record ActionProposal(
        String proposalId,
        long basedOnVersion,
        long basedOnPlanVersion,
        String executionId,
        List<ActionSpec> actions
) {
    public ActionProposal(String proposalId, long basedOnVersion, String executionId,
                          List<ActionSpec> actions) {
        this(proposalId, basedOnVersion, 1L, executionId, actions);
    }

    public ActionProposal {
        actions = actions == null ? List.of() : List.copyOf(actions);
        if (basedOnVersion < 0) basedOnVersion = 0;
        if (basedOnPlanVersion < 1) basedOnPlanVersion = 1;
    }
}
