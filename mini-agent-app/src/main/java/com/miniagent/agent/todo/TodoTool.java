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
 * 任务规划工具：模型自己拆任务、自己改状态。
 * 对标 hermes-agent 的 todo_tool。
 *
 * 当前会话由 {@link TaskTodoContext#currentSessionId} 提供（每轮 Agent 入口设置）。
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
                        - 复杂任务（>= 3 步）必须先用 action=set 写出完整计划，再开始执行。
                        - 每完成一个子任务，立刻 action=update 把对应 id 标为 completed。
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
                "description", "set 时使用，JSON 数组字符串：[{\"id\":1,\"content\":\"步骤说明\",\"status\":\"pending\"}]"
        ));
        params.put("id", Map.of(
                "type", "integer",
                "description", "update 时使用，要更新的子任务 id"
        ));
        params.put("status", Map.of(
                "type", "string",
                "description", "update 时使用，新状态：pending / in_progress / completed / cancelled"
        ));
        params.put("note", Map.of(
                "type", "string",
                "description", "update 时使用，可选的备注，例如失败原因或简短结果"
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
                    var updated = todoStore.update(sid, id, status, note);
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
