package com.miniagent.agent.planner;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;

/** planVersion 只在计划语义变化时递增，和 state version 分离。 */
public record PlanRevision(long planVersion, long parentPlanVersion, long createdAt, String reason) {
    public PlanRevision {
        if (planVersion < 1) planVersion = 1;
        if (parentPlanVersion < 0 || parentPlanVersion >= planVersion) parentPlanVersion = planVersion - 1;
        if (createdAt <= 0) createdAt = System.currentTimeMillis();
        reason = Objects.requireNonNullElse(reason, "").trim();
    }
    public static PlanRevision initial(String reason) { return new PlanRevision(1, 0, System.currentTimeMillis(), reason); }
    public PlanRevision next(String reason) { return new PlanRevision(planVersion + 1, planVersion, System.currentTimeMillis(), reason); }
    @JsonIgnore public String wireVersion() { return Long.toString(planVersion); }
}
