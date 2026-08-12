package com.miniagent.agent.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Proposal 硬闸门：工具面 + todo 动作约束（无 Spring 依赖，便于单测）。
 */
public final class ProposalGate {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProposalGate() {}

    /** @return 拒绝原因 JSON；null 表示放行 */
    public static String denyTool(String toolName) {
        PlanningContext.Holder h = PlanningContext.get();
        if (h == null || !h.hardGate()) return null;
        if (toolName == null || !h.allowedTools().contains(toolName))
            return err("Planner 硬闸门：本步仅允许工具 " + h.allowedTools() + "，拒绝: " + toolName);
        return null;
    }

    /**
     * @param action todo action
     * @param todoId update/reopen/confirm 的 id；set/list/clear 可传 0
     */
    public static String denyTodo(String action, int todoId) {
        PlanningContext.Holder h = PlanningContext.get();
        if (h == null || !h.hardGate()) return null;
        String a = action == null ? "" : action.trim().toLowerCase();
        if ("set".equals(a) || "clear".equals(a))
            return err("Planner 硬闸门：禁止 todo." + a + "（任务图由 Planner 投影）");
        if ("list".equals(a)) return null;
        if ("update".equals(a) || "reopen".equals(a) || "confirm".equals(a)) {
            if (h.focusTodoIds().isEmpty())
                return err("Planner 硬闸门：未绑定 focusTodoIds，拒绝 todo." + a);
            if (!h.focusTodoIds().contains(todoId))
                return err("Planner 硬闸门：只能操作当前步骤 todo id="
                        + h.focusTodoIds() + "，拒绝 id=" + todoId);
        }
        return null;
    }

    /** 从 todo 参数 JSON 解析 action / id 再校验 */
    public static String denyTodoArgsJson(String argumentsJson) {
        PlanningContext.Holder h = PlanningContext.get();
        if (h == null || !h.hardGate()) return null;
        try {
            JsonNode n = MAPPER.readTree(argumentsJson == null ? "{}" : argumentsJson);
            String action = n.path("action").asText("list");
            int id = n.path("id").asInt(0);
            return denyTodo(action, id);
        } catch (Exception e) {
            return err("Planner 硬闸门：todo 参数无法解析");
        }
    }

    private static String err(String msg) {
        return "{\"error\":\"" + msg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }
}
