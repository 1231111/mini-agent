package com.miniagent.agent.planner;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectReader;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Authoritative dispatch checks; no cached authorization or audit history is needed. */
public final class PlannerDispatchFence {
    // Keep every StateSnapshot/TaskNode constructor invariant. A partial JSON reader could
    // otherwise authorize a state which the normal snapshot reader rejects as corrupted.
    private static final ObjectReader SNAPSHOT = PlannerStateJson.MAPPER.copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .readerFor(StateSnapshot.class);

    private PlannerDispatchFence() { }

    public static boolean matches(StateSnapshot snapshot, String sessionId, ActionProposal proposal) {
        if (snapshot == null || sessionId == null || proposal == null || proposal.actions().isEmpty()
                || !Objects.equals(sessionId, snapshot.sessionId())
                || !Objects.equals(snapshot.executionId(), proposal.executionId())
                || snapshot.planVersion() != proposal.basedOnPlanVersion()) return false;
        Set<String> ids = new HashSet<>();
        Set<String> running = new HashSet<>();
        for (TaskNode node : snapshot.graph().nodes()) {
            if (node == null || node.id() == null || !ids.add(node.id())) return false;
            if (node.status() == TaskNodeStatus.RUNNING) running.add(node.id());
        }
        return proposal.actions().stream().allMatch(action -> action != null && running.contains(action.taskId()));
    }

    /** The indexed execution column and the JSON snapshot must describe the same stream. */
    public static boolean matchesJson(String json, String authoritativeExecutionId,
                                      String sessionId, ActionProposal proposal) {
        if (json == null || json.isBlank()) return false;
        try (JsonParser parser = SNAPSHOT.getFactory().createParser(json)) {
            StateSnapshot snapshot = SNAPSHOT.readValue(parser);
            // Check trailing content only at the root. Enabling the mapper-wide feature
            // also affects DoneWhen's nested readTree and rejects otherwise valid nodes.
            return parser.nextToken() == null && snapshot != null
                    && Objects.equals(authoritativeExecutionId, snapshot.executionId())
                    && matches(snapshot, sessionId, proposal);
        } catch (Exception malformedState) {
            return false;
        }
    }
}
