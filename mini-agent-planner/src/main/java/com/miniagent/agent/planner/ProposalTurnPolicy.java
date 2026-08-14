package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.core.LoopTurnPolicy;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** 提案步闸门：锁定工具面 + focus todo。 */
public final class ProposalTurnPolicy implements LoopTurnPolicy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> allowedTools;
    private final boolean hardGate;
    private final String focusLabel;
    private final Set<Integer> focusTodoIds;
    private final AtomicBoolean driftFlag = new AtomicBoolean(false);
    private final AtomicInteger driftCount = new AtomicInteger(0);

    public ProposalTurnPolicy(List<String> allowedTools, boolean hardGate,
                              String focusLabel, Set<Integer> focusTodoIds) {
        this.allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        this.hardGate = hardGate;
        this.focusLabel = focusLabel == null ? "" : focusLabel;
        this.focusTodoIds = focusTodoIds == null ? Set.of() : Set.copyOf(focusTodoIds);
    }

    @Override
    public List<String> allowedTools() { return allowedTools; }

    @Override
    public boolean forceToolsOnly() { return !allowedTools.isEmpty(); }

    @Override
    public boolean hardGate() { return hardGate; }

    @Override
    public String denyTool(String toolName) {
        if (!hardGate) return null;
        if (toolName == null || !allowedTools.contains(toolName))
            return err("Planner 硬闸门：本步仅允许工具 " + allowedTools + "，拒绝: " + toolName);
        return null;
    }

    @Override
    public String denyTodoArgs(String argumentsJson) {
        if (!hardGate) return null;
        try {
            JsonNode n = MAPPER.readTree(argumentsJson == null ? "{}" : argumentsJson);
            return denyTodo(n.path("action").asText("list"), n.path("id").asInt(0));
        } catch (Exception e) {
            return err("Planner 硬闸门：todo 参数无法解析");
        }
    }

    String denyTodo(String action, int todoId) {
        if (!hardGate) return null;
        String a = action == null ? "" : action.trim().toLowerCase();
        if ("set".equals(a) || "clear".equals(a))
            return err("Planner 硬闸门：禁止 todo." + a + "（任务图由 Planner 投影）");
        if ("list".equals(a)) return null;
        if ("update".equals(a) || "reopen".equals(a) || "confirm".equals(a)) {
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

    private static String err(String msg) {
        return "{\"error\":\"" + msg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }
}
