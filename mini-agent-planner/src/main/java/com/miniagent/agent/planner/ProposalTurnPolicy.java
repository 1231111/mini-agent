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
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 提案步闸门：锁定工具面 + focus todo。 */
public final class ProposalTurnPolicy implements LoopTurnPolicy {

    private static final int IDENTICAL_SUCCESS_OUTCOME_LIMIT = 3;
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
        return progressGuard.observe(toolName, argumentsJson, result, focusLabel);
    }

    @Override
    public String stopReason() { return progressGuard.stopReason(); }

    /** Shared by every LLM continuation chunk and re-proposal for one logical DAG node. */
    public static final class ProgressGuard {
        @FunctionalInterface
        public interface ToolCallBudget {
            /** Returns blank when reserved; otherwise returns the reason to stop the node. */
            String reserve();
        }

        private String lastOutcomeFingerprint = "";
        private int consecutiveIdenticalOutcomes;
        private int observedCalls;
        private volatile String stopReason = "";
        private final ToolCallBudget toolCallBudget;

        public ProgressGuard() { this(null); }

        public ProgressGuard(ToolCallBudget toolCallBudget) {
            this.toolCallBudget = toolCallBudget;
        }

        public synchronized String reserveToolInvocation() {
            if (toolCallBudget == null) return "";
            if (!stopReason.isBlank()) return stopReason;
            try {
                String denial = toolCallBudget.reserve();
                if (denial != null && !denial.isBlank()) stopReason = denial;
            } catch (RuntimeException persistenceFailure) {
                stopReason = "无法可靠保存当前节点的工具预算，系统已停止派发以避免重复副作用";
            }
            return stopReason;
        }

        public String stopReason() { return stopReason; }

        public synchronized String observe(String toolName, String argumentsJson,
                                           ToolResult result, String focusLabel) {
            if (result == null || result.status() == com.miniagent.agent.tool.ToolStatus.UNKNOWN
                    || result.status() == com.miniagent.agent.tool.ToolStatus.AWAITING_USER) return "";
            observedCalls++;
            String call = normalizeToolCall(toolName, argumentsJson);
            String outcome = result.status().name() + "|" + result.errorCode().name() + "|"
                    + digest(result.legacyText());
            String fingerprint = digest(call + "\n" + outcome);
            if (fingerprint.equals(lastOutcomeFingerprint)) consecutiveIdenticalOutcomes++;
            else {
                lastOutcomeFingerprint = fingerprint;
                consecutiveIdenticalOutcomes = 1;
            }
            if (consecutiveIdenticalOutcomes < IDENTICAL_SUCCESS_OUTCOME_LIMIT) return "";
            stopReason = "节点「" + (focusLabel == null || focusLabel.isBlank() ? "当前步骤" : focusLabel)
                    + "」连续 " + consecutiveIdenticalOutcomes + " 次重复相同工具调用并得到相同结果，未产生可验证的新进展。"
                    + "系统已停止该节点，保留当前状态，不会继续消耗工具预算。请检查验收目标、文件路径和工具调用是否匹配。";
            return stopReason;
        }

        public synchronized int observedCalls() { return observedCalls; }

        public synchronized int consecutiveIdenticalOutcomes() { return consecutiveIdenticalOutcomes; }

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
