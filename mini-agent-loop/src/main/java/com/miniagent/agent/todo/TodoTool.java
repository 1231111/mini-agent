package com.miniagent.agent.todo;

import org.springframework.beans.factory.annotation.Autowired;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.RunStatus;
import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.agent.tool.Tool;
import com.miniagent.agent.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * 任务规划工具：depends_on / 双轨验收 / reopen 回滚。
 */
@Slf4j
@Component
public class TodoTool {

    @Autowired
    private ToolRegistry toolRegistry;
    @Autowired
    private TaskTodoStore todoStore;
    @Autowired(required = false)
    private TraceRecorder traceRecorder;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostConstruct
    public void register() {
        toolRegistry.register(Tool.builder()
                .name("todo")
                .description("""
                        管理当前任务的执行计划（子任务列表）。
                        - 复杂任务必须先 action=set；每步必须含 done_when。
                        - 默认后一步 depends_on 前一步；可显式设 depends_on:[1,2] 或 depends_on:[]（无依赖/可并行）。
                        - completed 会做存在性 + 可插拔语义校验；依赖未满足或上游 hash 失效会拒绝。
                        - 关键步（依赖数>2 或交付/上线类）会进入 awaiting_confirm，必须 action=confirm 后才能执行。
                        - action=reopen 回滚 completed/blocked，并级联重置下游为 pending。
                        - 工具连败会 blocked；不要编造 completed。
                        - done_when：file_exists:… | media_delivered | note_required | llm_judge:评判标准
                        """)
                .parameters(buildSchema())
                .handler(this::handle)
                .build());
    }

    private Map<String, Object> buildSchema() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", Map.of(
                "type", "string",
                "description", "操作：set / update / list / clear / reopen / confirm",
                "required", true
        ));
        params.put("items", Map.of(
                "type", "string",
                "description", "set：JSON 数组，项可含 id,content,done_when,depends_on。例："
                        + "[{\"id\":1,\"content\":\"生图\",\"done_when\":\"media_delivered\"},"
                        + "{\"id\":2,\"content\":\"写入md\",\"done_when\":\"file_exists:workspace/a.md\",\"depends_on\":[1]}]"
        ));
        params.put("id", Map.of(
                "type", "integer",
                "description", "update/reopen 时的子任务 id"
        ));
        params.put("status", Map.of(
                "type", "string",
                "description", "update：pending / in_progress / completed / cancelled / blocked"
        ));
        params.put("note", Map.of(
                "type", "string",
                "description", "备注；reopen 时可写回滚原因；update 未传 evidence 时可用 note 充当证据"
        ));
        params.put("evidence", Map.of(
                "type", "string",
                "description", "completed 时必填：文件路径 / 图片 markdown / 验证摘要"
        ));
        return params;
    }

    @SuppressWarnings("unchecked")
    private String handle(String json) {
        try {
            String sid = TaskTodoContext.currentSessionId();
            json = alignTodoIdWithFocus(sid, json);
            String gate = com.miniagent.agent.core.LoopTurnContext.current().denyTodoArgs(json);
            if (gate != null) return gate;
            Map<String, Object> args = MAPPER.readValue(Optional.ofNullable(json).orElse("{}"), Map.class);
            String action = String.valueOf(args.getOrDefault("action", "list")).trim().toLowerCase();
            switch (action) {
                case "set" -> {
                    Object rawItems = args.get("items");
                    List<Map<String, Object>> items = parseItems(rawItems);
                    if (items.isEmpty()) {
                        return error("set 需要非空 items，每项含 content 与 done_when");
                    }
                    String missing = validateDoneWhen(items);
                    if (Objects.nonNull(missing)) return error(missing);
                    var updated = todoStore.set(sid, items);
                    String out = MAPPER.writeValueAsString(Map.of(
                            "success", true,
                            "action", "set",
                            "todo", todoStore.render(sid),
                            "stats", todoStore.stats(sid),
                            "items", updated
                    ));
                    traceTodo("TASK_SET", sid, "set", updated, null);
                    maybeTraceWaitingHuman(sid, updated);
                    return out;
                }
                case "update" -> {
                    int id = resolveTodoId(sid, coercePositiveInt(args.get("id")));
                    if (id <= 0) return error("update 缺少有效 id");
                    String status = (String) args.get("status");
                    String note = (String) args.get("note");
                    String evidence = (String) args.get("evidence");
                    String[] err = new String[1];
                    var updated = todoStore.update(sid, id, status, note, evidence, err);
                    if (Objects.isNull(updated)) {
                        return error(Objects.nonNull(err[0]) ? err[0] : "update 失败");
                    }
                    String out = MAPPER.writeValueAsString(Map.of(
                            "success", true,
                            "action", "update",
                            "todo", todoStore.render(sid),
                            "stats", todoStore.stats(sid),
                            "items", updated
                    ));
                    traceTodo("TASK_UPDATE", sid, "update", updated,
                            Map.of("id", id, "status", Optional.ofNullable(status).orElse(""),
                                    "note", Optional.ofNullable(note).orElse(""),
                                    "evidence", Optional.ofNullable(evidence).orElse("")));
                    maybeTraceWaitingHuman(sid, updated);
                    return out;
                }
                case "reopen" -> {
                    int id = resolveTodoId(sid, coercePositiveInt(args.get("id")));
                    if (id <= 0) return error("reopen 缺少有效 id");
                    String note = (String) args.get("note");
                    String[] err = new String[1];
                    var updated = todoStore.reopen(sid, id, note, err);
                    if (Objects.isNull(updated)) {
                        return error(Objects.nonNull(err[0]) ? err[0] : "reopen 失败");
                    }
                    String out = MAPPER.writeValueAsString(Map.of(
                            "success", true,
                            "action", "reopen",
                            "todo", todoStore.render(sid),
                            "stats", todoStore.stats(sid),
                            "items", updated
                    ));
                    traceTodo("TASK_REOPEN", sid, "reopen", updated, Map.of("id", id));
                    return out;
                }
                case "confirm" -> {
                    int id = resolveTodoId(sid, coercePositiveInt(args.get("id")));
                    if (id <= 0) return error("confirm 缺少有效 id");
                    String note = (String) args.get("note");
                    String[] err = new String[1];
                    var updated = todoStore.confirm(sid, id, Optional.ofNullable(note).orElse("CONFIRM"), err);
                    if (Objects.isNull(updated)) {
                        return error(Objects.nonNull(err[0]) ? err[0] : "confirm 失败");
                    }
                    String out = MAPPER.writeValueAsString(Map.of(
                            "success", true,
                            "action", "confirm",
                            "todo", todoStore.render(sid),
                            "stats", todoStore.stats(sid),
                            "items", updated
                    ));
                    traceTodo("TASK_CONFIRM", sid, "confirm", updated, Map.of("id", id));
                    return out;
                }
                case "list" -> {
                    var items = todoStore.get(sid);
                    String out = MAPPER.writeValueAsString(Map.of(
                            "success", true,
                            "action", "list",
                            "todo", todoStore.render(sid),
                            "stats", todoStore.stats(sid),
                            "items", items
                    ));
                    traceTodo("TASK_LIST", sid, "list", items, null);
                    return out;
                }
                case "clear" -> {
                    todoStore.clear(sid);
                    traceTodo("TASK_CLEAR", sid, "clear", List.of(), null);
                    return MAPPER.writeValueAsString(Map.of("success", true, "action", "clear"));
                }
                default -> {
                    return error("未知 action: " + action + "（支持 set/update/list/clear/reopen/confirm）");
                }
            }
        } catch (Exception e) {
            log.error("todo 工具执行失败", e);
            return error("todo 工具执行失败: " + e.getMessage());
        }
    }

    /**
     * 硬闸门前：错误/过期 todo id 对齐到 focus（优先 store 中存在的 focus id）。
     */
    private String alignTodoIdWithFocus(String sid, String json) {
        try {
            var h = com.miniagent.agent.core.LoopTurnContext.current();
            if (!h.hardGate() || h.focusTodoIds().isEmpty()) return json;
            var node = MAPPER.readTree(Optional.ofNullable(json).orElse("{}"));
            String action = node.path("action").asText("").trim().toLowerCase();
            if (!"update".equals(action) && !"reopen".equals(action) && !"confirm".equals(action))
                return json;
            int id = node.path("id").asInt(0);
            int preferred = -1;
            for (int f : h.focusTodoIds())
                if (hasTodo(sid, f)) {
                    preferred = f;
                    break;
                }
            if (preferred < 0)
                preferred = h.focusTodoIds().iterator().next();
            if (id == preferred || (h.focusTodoIds().contains(id) && hasTodo(sid, id)))
                return json;
            if (!(node instanceof com.fasterxml.jackson.databind.node.ObjectNode obj))
                return json;
            obj.put("id", preferred);
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * 模型常用旧 id（图 rewrite 后重编号）；硬闸门 focus 与 store 不一致时落到现存 focus / 当前子目标。
     */
    private int resolveTodoId(String sid, int id) {
        if (id > 0 && hasTodo(sid, id)) return id;
        var h = com.miniagent.agent.core.LoopTurnContext.current();
        for (int f : h.focusTodoIds())
            if (hasTodo(sid, f)) return f;
        TaskTodoStore.SubGoal sg = todoStore.currentSubGoalDetail(sid);
        if (sg != null && sg.id() > 0) return sg.id();
        return id;
    }

    private boolean hasTodo(String sid, int id) {
        for (TaskTodoStore.TodoItem it : todoStore.get(sid))
            if (it.id() == id) return true;
        return false;
    }

    private static String validateDoneWhen(List<Map<String, Object>> items) {
        for (Map<String, Object> raw : items) {
            if (Objects.isNull(raw)) continue;
            String content = String.valueOf(raw.getOrDefault("content", "")).trim();
            if (content.isEmpty()) continue;
            Object dw = raw.getOrDefault("done_when", raw.get("doneWhen"));
            if (Objects.isNull(dw) || StringUtils.isBlank(String.valueOf(dw))) {
                return "每项必须含 done_when（如 file_exists:workspace/xxx.md 或 media_delivered）。缺省项 content=" + content;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseItems(Object raw) throws Exception {
        if (Objects.isNull(raw)) return List.of();
        if (raw instanceof List<?> list) return (List<Map<String, Object>>) list;
        if (raw instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) return List.of();
            return MAPPER.readValue(trimmed, List.class);
        }
        return List.of();
    }

    private static int coercePositiveInt(Object raw) {
        if (raw instanceof Number n) return n.intValue();
        if (raw instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private void traceTodo(String stepType, String sid, String action, Object items, Map<String, Object> extra) {
        if (Objects.isNull(traceRecorder) || !traceRecorder.isActive()) return;
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("action", action);
            payload.put("sessionId", sid);
            if (Objects.nonNull(extra)) payload.putAll(extra);
            payload.put("items", items);
            if (Objects.nonNull(items) && items instanceof List<?> list) {
                payload.put("itemCount", list.size());
            }
            payload.put("render", todoStore.render(sid));
            payload.put("stats", todoStore.stats(sid));
            traceRecorder.recordNode(stepType, MAPPER.writeValueAsString(payload), RunStatus.SUCCESS.name(), 0);
        } catch (Exception e) {
            log.debug("todo trace 写入跳过: {}", e.getMessage());
        }
    }

    /** 关键步进入 awaiting_confirm 时记 WAITING_FOR_HUMAN */
    private void maybeTraceWaitingHuman(String sid, List<TaskTodoStore.TodoItem> items) {
        if (Objects.isNull(traceRecorder) || !traceRecorder.isActive() || Objects.isNull(items)) return;
        for (TaskTodoStore.TodoItem it : items) {
            if (it != null && it.status() == TaskTodoStore.Status.awaiting_confirm) {
                traceRecorder.recordWaitingForHuman(sid, 0,
                        "awaiting_confirm id=" + it.id() + " content=" + Optional.ofNullable(it.content()).orElse(""));
                return;
            }
        }
    }

    private String error(String msg) {
        try {
            return MAPPER.writeValueAsString(Map.of("success", false, "error", msg));
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + msg.replace("\"", "'") + "\"}";
        }
    }
}
