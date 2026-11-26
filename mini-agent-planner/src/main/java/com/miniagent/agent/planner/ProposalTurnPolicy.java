package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.agent.core.LoopTurnPolicy;
import com.miniagent.agent.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 提案步闸门：锁定工具面 + focus todo。 */
public final class ProposalTurnPolicy implements LoopTurnPolicy {

    private static final int IDENTICAL_OUTCOME_LIMIT = 3;
    private static final int MAX_TRACKED_OUTCOME_FINGERPRINTS = 256;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final List<String> allowedTools;
    private final boolean hardGate;
    private final String focusLabel;
    private final Set<Integer> focusTodoIds;
    private final int actionTimeoutSeconds;
    private final Set<String> declaredFilePaths;
    private final String requiredCommand;
    private final boolean scopedDagPolicy;
    private final ProgressGuard progressGuard;
    private final AtomicBoolean driftFlag = new AtomicBoolean(false);
    private final AtomicInteger driftCount = new AtomicInteger(0);

    public ProposalTurnPolicy(List<String> allowedTools, boolean hardGate,
                              String focusLabel, Set<Integer> focusTodoIds) {
        this(allowedTools, hardGate, focusLabel, focusTodoIds, 0, Set.of(), "", new ProgressGuard());
    }

    public ProposalTurnPolicy(List<String> allowedTools, boolean hardGate,
                              String focusLabel, Set<Integer> focusTodoIds,
                              int actionTimeoutSeconds) {
        this(allowedTools, hardGate, focusLabel, focusTodoIds, actionTimeoutSeconds,
                Set.of(), "", new ProgressGuard());
    }

    public ProposalTurnPolicy(List<String> allowedTools, boolean hardGate,
                              String focusLabel, Set<Integer> focusTodoIds,
                              int actionTimeoutSeconds, Set<String> declaredFilePaths,
                              String requiredCommand, ProgressGuard progressGuard) {
        this(allowedTools, hardGate, focusLabel, focusTodoIds, actionTimeoutSeconds,
                declaredFilePaths, requiredCommand, progressGuard, hardGate);
    }

    public ProposalTurnPolicy(List<String> allowedTools, boolean hardGate,
                              String focusLabel, Set<Integer> focusTodoIds,
                              int actionTimeoutSeconds, Set<String> declaredFilePaths,
                              String requiredCommand, ProgressGuard progressGuard,
                              boolean scopedDagPolicy) {
        this.allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        this.hardGate = hardGate;
        this.focusLabel = focusLabel == null ? "" : focusLabel;
        this.focusTodoIds = focusTodoIds == null ? Set.of() : Set.copyOf(focusTodoIds);
        this.actionTimeoutSeconds = Math.max(0, actionTimeoutSeconds);
        this.declaredFilePaths = declaredFilePaths == null ? Set.of() : declaredFilePaths.stream()
                .map(ProposalTurnPolicy::normalizeWorkspacePath).filter(path -> !path.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.requiredCommand = normalizeCommand(requiredCommand);
        this.scopedDagPolicy = scopedDagPolicy;
        this.progressGuard = progressGuard == null ? new ProgressGuard() : progressGuard;
    }

    @Override
    public List<String> allowedTools() { return allowedTools; }

    @Override
    public boolean forceToolsOnly() { return !allowedTools.isEmpty(); }

    @Override
    public boolean hardGate() { return hardGate; }

    @Override
    public String denyTool(String toolName) {
        if (!hardGate) {
            return null;
        }
        if (toolName == null || !allowedTools.contains(toolName))
            return err("Planner 硬闸门：本步仅允许工具 " + allowedTools + "，拒绝: " + toolName);
        return null;
    }

    @Override
    public String denyToolInvocation(String toolName, String argumentsJson) {
        String surfaceDeny = hardGate ? denyTool(toolName) : null;
        if (surfaceDeny != null) return surfaceDeny;
        if (scopedDagPolicy) {
            String budgetDeny = progressGuard.reserveToolInvocation();
            if (!budgetDeny.isBlank()) return err(budgetDeny);
        }
        if (!scopedDagPolicy) return null;
        String tool = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        JsonNode args;
        try {
            args = MAPPER.readTree(argumentsJson == null ? "{}" : argumentsJson);
        } catch (Exception invalid) {
            return err("Planner 硬闸门：工具参数无法解析，拒绝执行");
        }
        if ("write_file".equals(tool) || "edit_file".equals(tool)) {
            String path = normalizeWorkspacePath(args.path("path").asText(""));
            if (path.isBlank() || !declaredFilePaths.contains(path)) {
                return err("Planner 硬闸门：本节点只能写入已声明的交付路径 "
                        + declaredFilePaths + "；不得创建其他文件或整理/移动工作区文件");
            }
        }
        if ("undo_last_file_change".equals(tool)) {
            return err("Planner 硬闸门：自动执行的 DAG 节点不能撤销工作区文件；请修正当前声明路径");
        }
        if ("exec_command".equals(tool)) {
            String command = args.path("command").asText("");
            if (requiredCommand.isBlank()) {
                return err("Planner 硬闸门：当前节点没有 command_success 验收命令，禁止运行 shell；"
                        + "请使用本节点专用工具，或新增独立验收节点");
            }
            if (!requiredCommand.equals(normalizeCommand(command))) {
                return err("Planner 硬闸门：只允许运行本节点声明的验收命令：" + requiredCommand);
            }
        }
        return null;
    }

    @Override
    public String observeToolOutcome(String toolName, String argumentsJson, ToolResult result) {
        return progressGuard.observe(toolName, argumentsJson, result,
                result == null ? "" : result.legacyText(), focusLabel);
    }

    @Override
    public String observeToolOutcome(String toolName, String argumentsJson, ToolResult result,
                                     String visibleResult) {
        return progressGuard.observe(toolName, argumentsJson, result, visibleResult, focusLabel);
    }

    @Override
    public String stopReason() { return progressGuard.stopReason(); }

    @Override
    public StopKind stopKind() { return progressGuard.stopKind(); }

    /** Shared by every LLM continuation chunk and re-proposal for one logical DAG node. */
    public static final class ProgressGuard {
        public record BudgetDecision(boolean reserved, LoopTurnPolicy.StopKind stopKind, String reason) {
            public BudgetDecision {
                stopKind = stopKind == null ? LoopTurnPolicy.StopKind.NONE : stopKind;
                reason = reason == null ? "" : reason;
                if (reserved && stopKind != LoopTurnPolicy.StopKind.NONE)
                    throw new IllegalArgumentException("A reserved tool call cannot carry a stop kind");
            }

            public static BudgetDecision allowed() {
                return new BudgetDecision(true, LoopTurnPolicy.StopKind.NONE, "");
            }

            public static BudgetDecision denied(LoopTurnPolicy.StopKind kind, String reason) {
                if (kind == null || kind == LoopTurnPolicy.StopKind.NONE)
                    throw new IllegalArgumentException("A denied tool call requires a typed stop kind");
                return new BudgetDecision(false, kind, reason);
            }
        }

        @FunctionalInterface
        public interface ToolCallBudget {
            BudgetDecision reserve();
        }

        private String lastOutcomeFingerprint = "";
        private int consecutiveIdenticalOutcomes;
        private int observedCalls;
        /**
         * Counts exact tool-call/result pairs across the whole logical node, not
         * just adjacent calls. Access-order eviction bounds memory while keeping
         * frequently repeated failures resident between unrelated tool calls.
         */
        private final LinkedHashMap<String, Integer> outcomeOccurrences =
                new LinkedHashMap<>(64, 0.75f, true);
        private volatile String stopReason = "";
        private volatile LoopTurnPolicy.StopKind stopKind = LoopTurnPolicy.StopKind.NONE;
        private final ToolCallBudget toolCallBudget;

        public ProgressGuard() { this(null); }

        public ProgressGuard(ToolCallBudget toolCallBudget) {
            this.toolCallBudget = toolCallBudget;
        }

        public synchronized String reserveToolInvocation() {
            if (!stopReason.isBlank()) return stopReason;
            if (toolCallBudget == null) return "";
            try {
                BudgetDecision decision = toolCallBudget.reserve();
                if (decision == null || !decision.reserved()) {
                    stopReason = decision == null || decision.reason().isBlank()
                            ? "无法可靠保存当前节点的工具预算，系统已停止派发以避免重复副作用"
                            : decision.reason();
                    stopKind = decision == null || decision.stopKind() == LoopTurnPolicy.StopKind.NONE
                            ? LoopTurnPolicy.StopKind.NO_PROGRESS : decision.stopKind();
                }
            } catch (RuntimeException persistenceFailure) {
                stopReason = "无法可靠保存当前节点的工具预算，系统已停止派发以避免重复副作用";
                stopKind = LoopTurnPolicy.StopKind.NO_PROGRESS;
            }
            return stopReason;
        }

        public String stopReason() { return stopReason; }
        public LoopTurnPolicy.StopKind stopKind() { return stopKind; }

        public synchronized String observe(String toolName, String argumentsJson,
                                           ToolResult result, String focusLabel) {
            return observe(toolName, argumentsJson, result,
                    result == null ? "" : result.legacyText(), focusLabel);
        }

        public synchronized String observe(String toolName, String argumentsJson,
                                           ToolResult result, String visibleResult, String focusLabel) {
            if (!stopReason.isBlank()) return stopReason;
            if (result == null || result.status() == com.miniagent.agent.tool.ToolStatus.UNKNOWN
                    || result.status() == com.miniagent.agent.tool.ToolStatus.AWAITING_USER) return "";
            observedCalls++;
            String call = normalizeToolCall(toolName, argumentsJson);
            String outcome = result.status().name() + "|" + result.errorCode().name() + "|"
                    + digest(visibleResult);
            String fingerprint = digest(call + "\n" + outcome);
            if (fingerprint.equals(lastOutcomeFingerprint)) consecutiveIdenticalOutcomes++;
            else {
                lastOutcomeFingerprint = fingerprint;
                consecutiveIdenticalOutcomes = 1;
            }
            int occurrences = outcomeOccurrences.merge(fingerprint, 1, Integer::sum);
            evictOldOutcomeFingerprints();
            if (occurrences < IDENTICAL_OUTCOME_LIMIT) return "";
            String recurrence = occurrences == consecutiveIdenticalOutcomes ? "连续" : "累计";
            stopReason = "节点「" + (focusLabel == null || focusLabel.isBlank() ? "当前步骤" : focusLabel)
                    + "」" + recurrence + " " + occurrences
                    + " 次重复相同工具调用并得到相同结果，未产生可验证的新进展。"
                    + "系统已停止该节点，保留当前状态，不会继续消耗工具预算。请检查验收目标、文件路径和工具调用是否匹配。";
            stopKind = LoopTurnPolicy.StopKind.NO_PROGRESS;
            return stopReason;
        }

        private void evictOldOutcomeFingerprints() {
            while (outcomeOccurrences.size() > MAX_TRACKED_OUTCOME_FINGERPRINTS) {
                Iterator<Map.Entry<String, Integer>> iterator = outcomeOccurrences.entrySet().iterator();
                if (!iterator.hasNext()) return;
                iterator.next();
                iterator.remove();
            }
        }

        public synchronized int observedCalls() { return observedCalls; }

        public synchronized int consecutiveIdenticalOutcomes() { return consecutiveIdenticalOutcomes; }

        synchronized int trackedOutcomeFingerprints() { return outcomeOccurrences.size(); }

        private static String normalizeToolCall(String toolName, String argumentsJson) {
            String tool = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
            try {
                JsonNode parsed = MAPPER.readTree(argumentsJson == null ? "{}" : argumentsJson);
                if (parsed instanceof ObjectNode object) {
                    object.remove(List.of("description", "timeout", "outputEncoding"));
                    if ("exec_command".equals(tool)) {
                        String command = normalizeCommand(object.path("command").asText(""));
                        object.put("command", command);
                    }
                    return tool + "|" + MAPPER.writeValueAsString(object);
                }
            } catch (Exception ignored) {
                // Keep malformed calls distinct by their raw arguments.
            }
            return tool + "|" + (argumentsJson == null ? "" : argumentsJson.trim());
        }
    }

    static String normalizeWorkspacePath(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String path = raw.trim().replace('\\', '/');
        if (path.startsWith("./")) path = path.substring(2);
        if (path.startsWith("/") || path.matches("(?i)^[a-z]:.*")) return "";
        for (String segment : path.split("/")) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) return "";
        }
        return isWindows() ? path.toLowerCase(Locale.ROOT) : path;
    }

    static String normalizeCommand(String command) {
        if (command == null || command.isBlank()) return "";
        // This string is an execution authorization, not a display label. Do not
        // collapse quotes, path separators, case, or script paths: those changes
        // can make a different executable or an out-of-workspace script compare equal.
        return command.trim();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Override
    public String denyTodoArgs(String argumentsJson) {
        if (!hardGate) {
            return null;
        }
        try {
            JsonNode n = MAPPER.readTree(argumentsJson == null ? "{}" : argumentsJson);
            return denyTodo(n.path("action").asText("list"), n.path("id").asInt(0));
        } catch (Exception e) {
            return err("Planner 硬闸门：todo 参数无法解析");
        }
    }

    String denyTodo(String action, int todoId) {
        if (!hardGate) {
            return null;
        }
        String a = action == null ? "" : action.trim().toLowerCase();
        if ("set".equals(a) || "clear".equals(a))
            return err("Planner 硬闸门：禁止 todo." + a + "（任务图由 Planner 投影）");
        if ("list".equals(a)) {
            return null;
        }
        if ("submit".equals(a) || "update".equals(a) || "reopen".equals(a) || "confirm".equals(a)) {
            if (focusTodoIds.isEmpty())
                return err("Planner 硬闸门：未绑定 focusTodoIds，拒绝 todo." + a);
            if (!focusTodoIds.contains(todoId))
                return err("Planner 硬闸门：只能操作当前步骤 todo id="
                        + focusTodoIds + "，拒绝 id=" + todoId);
        }
        return null;
    }

    @Override
    public String focusLabel() { return focusLabel; }

    @Override
    public Set<Integer> focusTodoIds() { return focusTodoIds; }

    @Override
    public void markDrift() {
        driftFlag.set(true);
        driftCount.incrementAndGet();
    }

    @Override
    public int driftHits() { return driftCount.get(); }

    @Override
    public boolean consumeDrift() { return driftFlag.getAndSet(false); }

    @Override
    public int actionTimeoutSeconds() {
        return actionTimeoutSeconds;
    }

    private static String err(String msg) {
        return "{\"error\":\"" + msg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }
}
