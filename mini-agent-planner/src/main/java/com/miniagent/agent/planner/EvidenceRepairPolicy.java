package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.miniagent.agent.core.LoopTurnPolicy;
import com.miniagent.agent.todo.AcceptanceFeedback;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Persisted, same-node evidence collection. It cannot authorize product changes. */
public final class EvidenceRepairPolicy implements LoopTurnPolicy {
    static final String STATE_KEY = "acceptanceRepairs";
    static final int MAX_ATTEMPTS = 2;
    static final int MAX_CALLS_PER_ATTEMPT = 6;
    static final int MAX_TOTAL_CALLS = MAX_ATTEMPTS * MAX_CALLS_PER_ATTEMPT;
    static final int TIMEOUT_SECONDS = 60;
    private static final Set<String> READ_TOOLS = Set.of("read_file", "search_code", "list_files", "image_inspect");
    private final LoopTurnPolicy delegate;
    private final Set<String> paths;
    private final Supplier<String> reserve;
    private final List<String> allowed;
    private volatile String stopped = "";

    EvidenceRepairPolicy(LoopTurnPolicy delegate, Set<String> paths, Supplier<String> reserve) {
        this.delegate = delegate;
        this.paths = paths == null ? Set.of() : Set.copyOf(paths);
        this.reserve = reserve;
        LinkedHashSet<String> tools = new LinkedHashSet<>(READ_TOOLS);
        tools.add("todo");
        allowed = List.copyOf(tools);
    }

    public static String key(long planVersion, String nodeId) { return planVersion + ":" + nodeId; }
    static Map<String, Object> states(StateSnapshot snapshot) {
        Object raw = snapshot.execution().get(STATE_KEY);
        Map<String, Object> states = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> { if (key instanceof String s) states.put(s, value); });
        return states;
    }
    static Map<String, Object> state(StateSnapshot snapshot, String nodeId) {
        if (snapshot == null) return Map.of();
        Object raw = states(snapshot).get(key(snapshot.planVersion(), nodeId));
        Map<String, Object> state = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) map.forEach((key, value) -> { if (key instanceof String s) state.put(s, value); });
        return state;
    }
    static boolean active(StateSnapshot snapshot, String nodeId) {
        return "COLLECTING".equals(state(snapshot, nodeId).get("phase"));
    }
    static Set<String> allowedPaths(StateSnapshot snapshot, String nodeId) {
        Object raw = state(snapshot, nodeId).get("allowedPaths");
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (raw instanceof List<?> list) list.stream().filter(String.class::isInstance).map(String.class::cast).forEach(result::add);
        return Set.copyOf(result);
    }
    static StateSnapshot put(StateSnapshot snapshot, String nodeId, Map<String, Object> state) {
        Map<String, Object> states = states(snapshot);
        // A plan version is the authorization boundary; retain only this plan's repair state.
        String prefix = snapshot.planVersion() + ":";
        states.keySet().removeIf(key -> !key.startsWith(prefix));
        states.put(key(snapshot.planVersion(), nodeId), Map.copyOf(state));
        Map<String, Object> execution = new LinkedHashMap<>(snapshot.execution());
        execution.put(STATE_KEY, states);
        return snapshot.withExecution(execution);
    }
    static int count(Object value) { return value instanceof Number n ? Math.max(0, n.intValue()) : 0; }
    static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    static Map<String, Object> admission(StateSnapshot snapshot, TaskNode node, AcceptanceFeedback feedback,
                                         String evidence, Set<String> paths) {
        Map<String, Object> previous = state(snapshot, node.id());
        int attempts = count(previous.get("attempts"));
        List<String> fingerprints = new ArrayList<>();
        if (previous.get("fingerprints") instanceof List<?> list)
            list.stream().filter(String.class::isInstance).map(String.class::cast).forEach(fingerprints::add);
        String fingerprint = feedback.fingerprint();
        if (paths.isEmpty() || attempts >= MAX_ATTEMPTS || fingerprints.contains(fingerprint)
                || count(previous.get("totalCalls")) >= MAX_TOTAL_CALLS) return Map.of();
        fingerprints.add(fingerprint);
        Map<String, Object> next = new LinkedHashMap<>(previous);
        next.put("phase", "COLLECTING");
        next.put("attempts", attempts + 1);
        next.put("attemptCalls", 0);
        next.put("totalCalls", count(previous.get("totalCalls")));
        next.put("deadlineEpochMs", System.currentTimeMillis() + TIMEOUT_SECONDS * 1000L);
        next.put("fingerprints", fingerprints);
        next.put("feedback", PlannerStateJson.MAPPER.convertValue(feedback, Map.class));
        next.put("allowedPaths", List.copyOf(paths));
        if (!next.containsKey("originalEvidence")) next.put("originalEvidence", clip(evidence, 12000));
        return next;
    }
    static String reserve(PlannerStateStore store, String session, String execution, long planVersion, String nodeId) {
        for (int retry = 0; retry < 8; retry++) {
            StateSnapshot snapshot = store.get(session).orElse(null);
            if (snapshot == null || !execution.equals(snapshot.executionId()) || planVersion != snapshot.planVersion()
                    || snapshot.graph().byId(nodeId) == null || snapshot.graph().byId(nodeId).status() != TaskNodeStatus.RUNNING)
                return "补证据状态已变化，未派发工具";
            Map<String, Object> state = state(snapshot, nodeId);
            if (!"COLLECTING".equals(state.get("phase"))) return "当前节点不处于补证据阶段";
            if (System.currentTimeMillis() >= number(state.get("deadlineEpochMs"))
                    || count(state.get("attemptCalls")) >= MAX_CALLS_PER_ATTEMPT
                    || count(state.get("totalCalls")) >= MAX_TOTAL_CALLS)
                return "只读补证据预算已耗尽，保留进度等待处理";
            state.put("attemptCalls", count(state.get("attemptCalls")) + 1);
            state.put("totalCalls", count(state.get("totalCalls")) + 1);
            try { store.commit(session, snapshot.version(), put(snapshot, nodeId, state)); return ""; }
            catch (PlannerStateStore.VersionConflictException conflict) { /* CAS retry, never dispatch without receipt. */ }
        }
        return "无法持久化补证据工具预算，未派发工具";
    }
    static String prompt(Map<String, Object> state) {
        return "【同节点只读补证据】原执行已完成，本阶段禁止重新生成、修改、删除产物或执行任何命令。"
                + "只读取已授权路径，按下列冻结条件缺口提交本轮真实回执，随后由独立验收重新判定。\n"
                + "缺口=" + state.get("feedback") + "\n允许路径=" + state.get("allowedPaths")
                + "\n原执行回执（只供核验，不代表本次新证据）=" + state.get("originalEvidence");
    }

    @Override public String denyTool(String tool) {
        return allowed.contains(tool) ? null : denied("补证据只允许文件只读取证和当前 todo.submit；禁止工具 " + tool);
    }
    private String check(String tool, String args) {
        String denied = denyTool(tool);
        if (denied != null) return denied;
        try {
            JsonNode json = PlannerStateJson.MAPPER.readTree(args == null ? "{}" : args);
            if (json == null || !json.isObject()) return denied("补证据参数必须为对象");
            if ("todo".equals(tool)) return denyTodoArgs(args);
            String path = ProposalTurnPolicy.normalizeWorkspacePath(json.path("path").asText(""));
            if (path.isBlank() || !paths.contains(path)) return denied("补证据路径未获授权，允许路径=" + paths);
            return null;
        } catch (Exception malformed) { return denied("补证据参数无法可靠校验"); }
    }
    @Override public String denyToolInvocation(String tool, String args) {
        String denied = check(tool, args);
        if (denied != null) return denied;
        if (!stopped.isBlank()) return denied(stopped);
        String result = reserve.get();
        if (result != null && !result.isBlank()) { stopped = result; return denied(result); }
        return null;
    }
    @Override public String denyRewrittenToolInvocation(String tool, String args) { return check(tool, args); }
    @Override public String denyTodoArgs(String args) {
        try {
            JsonNode json = PlannerStateJson.MAPPER.readTree(args == null ? "{}" : args);
            String action = json.path("action").asText("");
            if (!Set.of("submit", "list").contains(action)) return denied("补证据只能提交当前节点证据，不能修改任务状态");
            if ("submit".equals(action) && !focusTodoIds().contains(json.path("id").asInt(0)))
                return denied("补证据只能提交当前节点 todo id=" + focusTodoIds());
            return null;
        } catch (Exception malformed) { return denied("补证据 todo 参数无效"); }
    }
    @Override public List<String> allowedTools() { return allowed; }
    @Override public boolean hardGate() { return true; }
    @Override public boolean forceToolsOnly() { return true; }
    @Override public String focusLabel() { return delegate.focusLabel(); }
    @Override public Set<Integer> focusTodoIds() { return delegate.focusTodoIds(); }
    @Override public void markDrift() { delegate.markDrift(); }
    @Override public int driftHits() { return delegate.driftHits(); }
    @Override public boolean consumeDrift() { return delegate.consumeDrift(); }
    @Override public int actionTimeoutSeconds() { return TIMEOUT_SECONDS; }
    @Override public String stopReason() { return stopped; }
    @Override public StopKind stopKind() { return stopped.isBlank() ? StopKind.NONE : StopKind.NO_PROGRESS; }
    @Override public String observeToolOutcome(String tool, String args, ToolResult result) { return delegate.observeToolOutcome(tool, args, result); }
    @Override public String observeToolOutcome(String tool, String args, ToolResult result, String visible) { return delegate.observeToolOutcome(tool, args, result, visible); }
    private static String denied(String reason) { return ToolResult.failure(ToolErrorCode.PERMISSION_DENIED, reason, false).legacyText(); }
    private static String clip(String text, int cap) { return text == null ? "" : text.substring(0, Math.min(text.length(), cap)); }
}
