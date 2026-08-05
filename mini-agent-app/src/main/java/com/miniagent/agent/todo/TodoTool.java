package com.miniagent.agent.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.Tool;
import com.miniagent.agent.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务规划工具：模型自己拆任务、自己改状态，completed 需对照 done_when 提供 evidence。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TodoTool {

    private final ToolRegistry toolRegistry;
    private final TaskTodoStore todoStore;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostConstruct
    public void register() {
        toolRegistry.register(Tool.builder()
                .name("todo")
                .description("""
                        管理当前任务的执行计划（子任务列表）。
                        - 复杂任务必须先 action=set 写出完整计划（每步含 done_when 验收标准），再开始执行。
                        - 每完成一步立刻 action=update 标 completed，并提供 evidence（文件路径/图片链接/验证结果）。
                        - done_when 推荐：file_exists:workspace/xxx.md | media_delivered | note_required
                        - 简单一句话问答不需要使用此工具。
                        """)
                .parameters(buildSchema())
                .handler(this::handle)
                .build());
    }

    private Map<String, Object> buildSchema() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", Map.of(
                "type", "string",
                "description", "操作：set / update / list / clear",
                "required", true
        ));
        params.put("items", Map.of(
                "type", "string",
                "description", "set 时使用，JSON 数组：[{\"id\":1,\"content\":\"步骤\",\"status\":\"pending\",\"done_when\":\"file_exists:workspace/a.md\"}]"
        ));
        params.put("id", Map.of(
                "type", "integer",
                "description", "update 时使用，要更新的子任务 id"
        ));
        params.put("status", Map.of(
                "type", "string",
                "description", "update 时使用：pending / in_progress / completed / cancelled"
        ));
        params.put("note", Map.of(
                "type", "string",
                "description", "update 时可选备注；若未传 evidence 可用 note 充当证据"
        ));
        params.put("evidence", Map.of(
                "type", "string",
                "description", "update 标 completed 时必填：完成证据（文件路径、图片 markdown/URL、命令结果摘要）"
        ));
        return params;
    }

    @SuppressWarnings("unchecked")
    private String handle(String json) {
        try {
            String sid = TaskTodoContext.currentSessionId();
            Map<String, Object> args = MAPPER.readValue(json == null ? "{}" : json, Map.class);
            String action = String.valueOf(args.getOrDefault("action", "list")).trim().toLowerCase();
            switch (action) {
                case "set" -> {
                    Object rawItems = args.get("items");
                    List<Map<String, Object>> items = parseItems(rawItems);
                    if (items.isEmpty()) {
                        return error("set 需要非空 items，每项含 content，建议含 done_when");
                    }
                    var updated = todoStore.set(sid, items);
                    return MAPPER.writeValueAsString(Map.of(
                            "success", true,
                            "action", "set",
                            "todo", todoStore.render(sid),
                            "stats", todoStore.stats(sid),
                            "items", updated
                    ));
                }
                case "update" -> {
                    int id = args.get("id") instanceof Number n ? n.intValue() : -1;
                    if (id <= 0) return error("update 缺少有效 id");
                    String status = (String) args.get("status");
                    String note = (String) args.get("note");
                    String evidence = (String) args.get("evidence");
                    String[] err = new String[1];
                    var updated = todoStore.update(sid, id, status, note, evidence, err);
                    if (updated == null) {
                        return error(err[0] != null ? err[0] : "update 失败");
                    }
                    return MAPPER.writeValueAsString(Map.of(
                            "success", true,
                            "action", "update",
                            "todo", todoStore.render(sid),
                            "stats", todoStore.stats(sid),
                            "items", updated
                    ));
                }
                case "list" -> {
                    return MAPPER.writeValueAsString(Map.of(
                            "success", true,
                            "action", "list",
                            "todo", todoStore.render(sid),
                            "stats", todoStore.stats(sid),
                            "items", todoStore.get(sid)
                    ));
                }
                case "clear" -> {
                    todoStore.clear(sid);
                    return MAPPER.writeValueAsString(Map.of("success", true, "action", "clear"));
                }
                default -> {
                    return error("未知 action: " + action + "（支持 set/update/list/clear）");
                }
            }
        } catch (Exception e) {
            log.error("todo 工具执行失败", e);
            return error("todo 工具执行失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseItems(Object raw) throws Exception {
        if (raw == null) return List.of();
        if (raw instanceof List<?> list) return (List<Map<String, Object>>) list;
        if (raw instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) return List.of();
            return MAPPER.readValue(trimmed, List.class);
        }
        return List.of();
    }

    private String error(String msg) {
        try {
            return MAPPER.writeValueAsString(Map.of("success", false, "error", msg));
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + msg.replace("\"", "'") + "\"}";
        }
    }
}
