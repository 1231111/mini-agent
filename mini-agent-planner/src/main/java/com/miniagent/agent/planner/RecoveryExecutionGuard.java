package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.ToolConcurrencyPolicy;
import com.miniagent.agent.tool.CapabilityRegistry;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * Admission check for side effects in a recovery plan.
 *
 * <p>A semantic failure means that the previous action may already have
 * changed the world.  A newly compiled recovery node therefore cannot simply
 * repeat the write because it has a new node id/idempotency key.  It must first
 * carry a paired, successful read receipt from the same execution and plan
 * revision.  This class is deliberately pure: it does not run tools, mutate
 * state, or treat model claims as evidence.  The dispatch boundary should call
 * {@link #check(StateSnapshot, TaskNode, ActionSpec, Verification)} immediately
 * before any bound action is executed.
 */
public final class RecoveryExecutionGuard {
    /** Persisted in the planner snapshot, so recovery provenance survives a worker restart. */
    public static final String STATE_KEY = "recoveryExecutionGuards";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> READ_TOOLS = Set.of(
            "read_file", "search_code", "list_files", "read_package", "image_inspect",
            "browser_snapshot", "browser_extract_text", "browser_form_inspect", "desktop_observe");

    private RecoveryExecutionGuard() { }

    public record Verification(String executionId, long planVersion, String nodeId,
                               ExecutionEvidence evidence) {
        public Verification {
            executionId = executionId == null ? "" : executionId.trim();
            nodeId = nodeId == null ? "" : nodeId.trim();
            evidence = evidence == null ? ExecutionEvidence.claims("") : evidence;
        }

        public static Verification of(StateSnapshot snapshot, String nodeId,
                                      ExecutionEvidence evidence) {
            return new Verification(snapshot == null ? "" : snapshot.executionId(),
                    snapshot == null ? -1 : snapshot.planVersion(), nodeId, evidence);
        }
    }

    public record Check(boolean allowed, String code, String reason) {
        public Check {
            code = code == null ? "" : code;
            reason = reason == null ? "" : reason;
        }
        public static Check allow() { return new Check(true, "", ""); }
        public static Check deny(String code, String reason) { return new Check(false, code, reason); }
    }

    /**
     * Mark the non-success nodes of a newly compiled recovery graph.  Call this
     * after {@code revisePlan}, using the new snapshot as {@code target} and the
     * old snapshot as {@code source}.  The metadata is deliberately separate
     * from tool arguments and model-visible error text: it can never be passed
     * to a built-in tool as an unrecognised argument or forged by the model.
     */
    public static StateSnapshot register(StateSnapshot target, StateSnapshot source,
                                         TaskGraph candidate) {
        if (target == null || source == null || candidate == null) return target;
        Map<String, Object> records = new LinkedHashMap<>();
        String prefix = target.planVersion() + ":";
        List<Map<String, Object>> sourceEffects = sourceEffects(source);
        if (sourceEffects.isEmpty()) {
            Map<String, Object> execution = new LinkedHashMap<>(target.execution());
            execution.remove(STATE_KEY);
            return target.withExecution(execution);
        }
        List<String> sourceNodeIds = sourceEffects.stream()
                .map(effect -> String.valueOf(effect.get("nodeId"))).distinct().toList();
        for (TaskNode node : candidate.nodes()) {
            if (node == null || node.status() == TaskNodeStatus.SUCCESS
                    || node.status() == TaskNodeStatus.CANCELLED) continue;
            records.put(prefix + node.id(), Map.of(
                    "executionId", source.executionId() == null ? "" : source.executionId(),
                    "sourcePlanVersion", source.planVersion(),
                    "sourceNodeIds", sourceNodeIds,
                    "sourceEffects", sourceEffects,
                    "targetPlanVersion", target.planVersion()));
        }
        Map<String, Object> execution = new LinkedHashMap<>(target.execution());
        execution.put(STATE_KEY, records);
        return target.withExecution(execution);
    }

    /** Facts only: complete paired mutation observations, or conservative legacy write metadata. */
    private static List<Map<String, Object>> sourceEffects(StateSnapshot source) {
        List<Map<String, Object>> effects = new ArrayList<>();
        // A second replan must not erase the first plan's side effects merely
        // because its replacement has not yet dispatched another tool.
        for (TaskNode node : source.graph().nodes()) {
            if (node.status() == TaskNodeStatus.SUCCESS || node.status() == TaskNodeStatus.CANCELLED) continue;
            Object previous = records(source).get(source.planVersion() + ":" + node.id());
            if (!(previous instanceof Map<?, ?> record)
                    || !(record.get("sourceEffects") instanceof List<?> inherited)) continue;
            for (Object raw : inherited) {
                if (!(raw instanceof Map<?, ?> effect)) continue;
                Map<String, Object> copy = new LinkedHashMap<>();
                for (String key : List.of("nodeId", "tool", "target", "status", "resultSha256")) {
                    Object value = effect.get(key);
                    if (value instanceof String text) copy.put(key, text);
                }
                if (copy.size() == 5 && !effects.contains(copy)) effects.add(Map.copyOf(copy));
            }
        }
        for (TaskNode node : source.graph().nodes()) {
            if (node.status() == TaskNodeStatus.SUCCESS || node.status() == TaskNodeStatus.CANCELLED) continue;
            ExecutionEvidence evidence = ExecutionEvidence.fromEnvelope(node.output());
            boolean pairedEffect = false;
            for (ExecutionEvidence.Observation observation : evidence.observations()) {
                if (CapabilityRegistry.FALLBACK_TOOL.equals(observation.toolName())
                        || ToolConcurrencyPolicy.isReadOnly(observation.toolName(), observation.arguments())
                        || observation.callMessageIndex() < 0
                        || observation.resultMessageIndex() <= observation.callMessageIndex()) continue;
                pairedEffect = true;
                effects.add(Map.of("nodeId", node.id(), "tool", observation.toolName(),
                        "target", targetFromJson(observation.arguments()), "status", observation.status(),
                        "resultSha256", observation.resultSha256()));
            }
            if (pairedEffect) continue;
            ActionBinder.Bound bound = ActionBinder.bind(node, source.graph());
            boolean hasPairedReads = !evidence.observations().isEmpty() && evidence.completeness().complete();
            boolean legacyWrite = !hasPairedReads && !node.output().isBlank()
                    && CapabilityRegistry.persistsArtifacts(node.capability());
            boolean toolAttemptedWrite = !hasPairedReads && node.toolStatus() != null
                    && !ToolConcurrencyPolicy.isReadOnly(bound.tool(), argumentsJson(bound.arguments()))
                    && !CapabilityRegistry.FALLBACK_TOOL.equals(bound.tool())
                    && (!bound.tool().equals(node.capability())
                        || CapabilityRegistry.persistsArtifacts(node.capability()));
            if (legacyWrite || toolAttemptedWrite) {
                effects.add(Map.of("nodeId", node.id(), "tool", bound.tool(),
                        "target", targetPath(bound.arguments()),
                        "status", node.toolStatus() == null ? "LEGACY" : node.toolStatus().name(),
                        "resultSha256", ""));
            }
        }
        return List.copyOf(effects);
    }

    private static String targetFromJson(String args) {
        try {
            JsonNode parsed = JSON.readTree(args);
            if (parsed == null || !parsed.isObject()) return "";
            for (String key : List.of("path", "file", "target", "url")) {
                JsonNode value = parsed.get(key);
                if (value != null && value.isTextual() && !value.textValue().isBlank()) return value.textValue();
            }
        } catch (Exception ignored) { }
        return "";
    }

    /**
     * Check a candidate action. Ordinary nodes are unaffected; only nodes
     * carrying recovery provenance are subject to this admission gate.
     */
    public static Check check(StateSnapshot snapshot, TaskNode node, ActionSpec action,
                              Verification verification) {
        if (snapshot == null || node == null || action == null) {
            return Check.deny("RECOVERY_GUARD_INPUT_INVALID", "恢复副作用守卫缺少状态、节点或动作");
        }
        if (snapshot.graph().byId(node.id()) == null || !Objects.equals(node.id(), action.taskId())) {
            return Check.deny("RECOVERY_GUARD_SCOPE_INVALID", "恢复副作用动作不属于当前节点，拒绝执行");
        }
        if (!requiresVerification(snapshot, node)) return Check.allow();
        if (ToolConcurrencyPolicy.isReadOnly(action.tool(), argumentsJson(action.arguments()))) {
            return Check.allow();
        }
        if (verification == null) {
            return Check.deny("RECOVERY_SIDE_EFFECT_REQUIRES_READBACK",
                    "恢复节点可能重复副作用，必须先完成同一执行轮的只读核验");
        }
        if (!Objects.equals(snapshot.executionId(), verification.executionId())
                || snapshot.planVersion() != verification.planVersion()
                || !verification.nodeId().equals(node.id())) {
            return Check.deny("RECOVERY_VERIFICATION_SCOPE_MISMATCH",
                    "只读核验不属于当前 executionId/planVersion/node，禁止重放副作用");
        }
        ExecutionEvidence evidence = verification.evidence();
        if (evidence == null || !evidence.completeness().complete()
                || evidence.completeness().unpairedCallCount() != 0
                || evidence.completeness().unpairedResultCount() != 0
                || evidence.completeness().omittedObservationCount() != 0
                || evidence.completeness().truncated()) {
            return Check.deny("RECOVERY_READBACK_INCOMPLETE",
                    "恢复前只读回执不完整、未配对或被截断，不能证明既有副作用状态");
        }
        String target = targetPath(action.arguments());
        if (target.isBlank()) return Check.deny("RECOVERY_TARGET_UNVERIFIABLE",
                "恢复副作用缺少可绑定的目标，必须先规划明确目标和独立只读核验，不能用任意读取代替");
        boolean matched = evidence.observations().stream()
                .filter(RecoveryExecutionGuard::successfulRead)
                .anyMatch(observation -> observationMatches(observation, target));
        return matched ? Check.allow() : Check.deny("RECOVERY_READBACK_MISSING",
                target.isBlank() ? "恢复副作用前缺少成功的只读回执" :
                        "恢复副作用前缺少目标路径的成功只读回执: " + target);
    }

    /** Only persisted planner metadata activates this gate; text claims do not. */
    static boolean requiresVerification(StateSnapshot snapshot, TaskNode node) {
        if (snapshot == null || node == null) return false;
        return records(snapshot).containsKey(snapshot.planVersion() + ":" + node.id());
    }

    private static Map<String, Object> records(StateSnapshot snapshot) {
        Map<String, Object> records = new LinkedHashMap<>();
        if (snapshot == null) return records;
        Object raw = snapshot.execution().get(STATE_KEY);
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> { if (key instanceof String s && value instanceof Map<?, ?>) records.put(s, value); });
        }
        return records;
    }

    private static boolean successfulRead(ExecutionEvidence.Observation observation) {
        return observation != null && observation.success()
                && !observation.truncated()
                && observation.callMessageIndex() >= 0
                && observation.resultMessageIndex() > observation.callMessageIndex()
                && observation.sequence() >= 0 && observation.batch() >= 0
                && "SUCCESS".equals(observation.status())
                && observation.resultSha256().matches("[0-9a-f]{64}")
                && READ_TOOLS.contains(observation.toolName());
    }

    private static boolean observationMatches(ExecutionEvidence.Observation observation, String target) {
        try {
            JsonNode args = JSON.readTree(observation.arguments());
            if (args == null || !args.isObject()) return false;
            String expected = normalize(target);
            for (String key : List.of("path", "file", "target", "url")) {
                JsonNode path = args.get(key);
                if (path != null && path.isTextual() && expected.equals(normalize(path.textValue()))) return true;
            }
            return false;
        } catch (Exception invalid) {
            return false;
        }
    }

    private static String targetPath(Map<String, Object> arguments) {
        if (arguments == null) return "";
        for (String key : List.of("path", "file", "target", "url")) {
            Object value = arguments.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }

    private static String argumentsJson(Map<String, Object> arguments) {
        try { return JSON.writeValueAsString(arguments == null ? Map.of() : arguments); }
        catch (Exception ignored) { return ""; }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String normalized = value.replace('\\', '/').trim();
        boolean windowsPath = normalized.matches("^[A-Za-z]:/.*");
        // URL paths and Unix paths are case-sensitive; do not conflate them.
        return windowsPath ? normalized.toLowerCase(Locale.ROOT) : normalized;
    }
}
