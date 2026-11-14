package com.miniagent.agent.planner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Append-only bounded audit history. Persistence merges deltas while holding its stream lock. */
public final class PlannerEventHistory {
    private static final int MAX_EVENTS = 500;
    private static final int TRIM_TO = 400;
    private PlannerEventHistory() { }

    public static List<DomainEvent> append(List<DomainEvent> current, List<DomainEvent> additions) {
        List<DomainEvent> merged = new ArrayList<>(current == null ? List.of() : current);
        Set<String> ids = new HashSet<>();
        for (DomainEvent event : merged) if (event.eventId() != null) ids.add(event.eventId());
        for (DomainEvent event : additions == null ? List.<DomainEvent>of() : additions) {
            if (event == null || (event.eventId() != null && !ids.add(event.eventId()))) continue;
            if (event.eventId() == null && merged.contains(event)) continue;
            merged.add(event);
            if (merged.size() > MAX_EVENTS) merged = new ArrayList<>(merged.subList(merged.size() - TRIM_TO, merged.size()));
        }
        return List.copyOf(merged);
    }
}
