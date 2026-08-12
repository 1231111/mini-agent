package com.miniagent.agent.planner;

import java.time.Instant;
import java.util.Map;

public record DomainEvent(
        String eventId,
        DomainEventType type,
        String actionId,
        String taskId,
        Map<String, Object> payload,
        Instant at
) {
    public DomainEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        at = at == null ? Instant.now() : at;
    }
}
