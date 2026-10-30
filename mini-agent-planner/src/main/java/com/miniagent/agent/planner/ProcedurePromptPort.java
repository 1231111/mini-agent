package com.miniagent.agent.planner;

import java.util.List;

/** Optional adapter for procedures authorized to the currently bound owner. */
public interface ProcedurePromptPort {
    List<ProcedurePromptInput> findForCurrentOwner(String query);

    default List<ProcedurePromptInput> bindReferences(String query, List<ProcedurePromptInput> requested) {
        List<ProcedurePromptInput> allowed = findForCurrentOwner(query);
        if (allowed == null || requested == null) return List.of();
        return requested.stream().map(r -> allowed.stream().filter(a ->
                java.util.Objects.equals(a.procedureId(), r.procedureId())
                        && java.util.Objects.equals(a.version(), r.version())).findFirst().orElse(null))
                .filter(java.util.Objects::nonNull).toList();
    }
}
