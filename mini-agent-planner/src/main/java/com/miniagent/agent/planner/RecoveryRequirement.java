package com.miniagent.agent.planner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable recovery intent, independent of a judge's optional structured feedback. */
final class RecoveryRequirement {
    static final String KEY = "recoveryRequirements";

    private RecoveryRequirement() { }

    static StateSnapshot remember(StateSnapshot snapshot, TaskNode node, FailureDiagnosis diagnosis) {
        if (snapshot == null || node == null || diagnosis == null) return snapshot;
        if (diagnosis.failureClass() != FailureClass.REWRITE_GRAPH
                && diagnosis.failureClass() != FailureClass.REVISE_GOAL) return snapshot;
        Map<String, Object> records = records(snapshot);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("sessionId", snapshot.sessionId());
        entry.put("executionId", snapshot.executionId());
        entry.put("planVersion", snapshot.planVersion());
        entry.put("nodeId", node.id());
        entry.put("diagnosis", PlannerStateJson.MAPPER.convertValue(diagnosis, Map.class));
        records.put(key(snapshot, node.id()), entry);
        Map<String, Object> execution = new LinkedHashMap<>(snapshot.execution());
        execution.put(KEY, records);
        return snapshot.withExecution(execution);
    }

    static FailureDiagnosis diagnosis(StateSnapshot snapshot, TaskNode node) {
        if (snapshot == null || node == null) return null;
        Object raw = records(snapshot).get(key(snapshot, node.id()));
        if (!(raw instanceof Map<?, ?> record)
                || !snapshot.sessionId().equals(record.get("sessionId"))
                || !snapshot.executionId().equals(record.get("executionId"))
                || !(record.get("planVersion") instanceof Number version)
                || snapshot.planVersion() != version.longValue()
                || !node.id().equals(record.get("nodeId"))) return null;
        try {
            FailureDiagnosis diagnosis = PlannerStateJson.MAPPER.convertValue(record.get("diagnosis"), FailureDiagnosis.class);
            return diagnosis != null && node.id().equals(diagnosis.taskId()) ? diagnosis : null;
        } catch (IllegalArgumentException invalid) { return null; }
    }

    /** Old blocked snapshots must be reanalyzed after confirmation, not blindly replayed. */
    static StateSnapshot migrateLegacyWait(StateSnapshot snapshot) {
        Object raw = snapshot.execution().get(PlanningLoop.EXECUTION_HUMAN_WAIT);
        if (!(raw instanceof Map<?, ?> wait)
                || !"RECOVERY_ANALYSIS_BLOCKED".equals(wait.get("reason"))
                || !(wait.get("taskIds") instanceof List<?> ids)) return snapshot;
        StateSnapshot result = snapshot;
        for (Object id : ids) {
            TaskNode node = id instanceof String text ? snapshot.graph().byId(text) : null;
            if (node == null || diagnosis(result, node) != null) continue;
            result = remember(result, node, new FailureDiagnosis(FailureClass.REWRITE_GRAPH,
                    FailureKind.EVAL_FAILED, node.id(), node.toolHint(), node.lastError(),
                    "重新分析已保存的失败原因，保留原验收契约；核验现状后再规划未完成工作"));
        }
        return result;
    }

    static Map<String, Object> afterReplan(Map<String, Object> execution) {
        Map<String, Object> result = new LinkedHashMap<>(execution);
        result.remove(KEY);
        result.remove(PlanningLoop.EXECUTION_HUMAN_WAIT);
        return result;
    }

    private static String key(StateSnapshot snapshot, String id) { return snapshot.planVersion() + ":" + id; }

    private static Map<String, Object> records(StateSnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object raw = snapshot.execution().get(KEY);
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> {
            if (key instanceof String text) result.put(text, value);
        });
        return result;
    }
}
