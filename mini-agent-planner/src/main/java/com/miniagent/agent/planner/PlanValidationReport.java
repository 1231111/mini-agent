package com.miniagent.agent.planner;

import java.util.List;

public record PlanValidationReport(List<String> errors, List<String> warnings) {
    public PlanValidationReport {
        errors = errors == null ? List.of() : List.copyOf(errors);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
    public boolean valid() { return errors.isEmpty(); }
    public String summary() { return "errors=" + errors + ", warnings=" + warnings; }
}
