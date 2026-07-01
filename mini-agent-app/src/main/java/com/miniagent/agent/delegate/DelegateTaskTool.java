package com.miniagent.agent.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.tool.Tool;
import com.miniagent.agent.tool.ToolRegistry;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 子任务派发工具：给主 Agent 一个“分包”能力。
 * 对标 hermes-agent 的 delegate_task：
 *   - 子 Agent 拿到 fresh context（只有 goal + context）
 *   - 受限工具集（默认只有 read_file/list_files/web_search/web_extract）
 *   - 只把最终回答（< 1500 字）塞回主 Agent 的 tool 结果里
 *   - 主上下文不会被子任务的中间步骤污染
 */
@Slf4j
@Component
public class DelegateTaskTool {

    @Autowired
    private ToolRegistry toolRegistry;
    @Autowired
    private AgentLoop agentLoop;
    @Autowired
    private ChatModel chatModel;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 子 Agent 默认可用的工具集合（读 + 调研 + 产出）。 */
    private static final List<String> DEFAULT_SUBAGENT_TOOLS = List.of(
            "read_file", "list_files", "read_package", "write_file", "exec_command",
            "search_code", "edit_file", "ast_search", "codebase_search",
            "web_search", "web_extract", "http_get"
    );

    private static final int SUBAGENT_MAX_ITERATIONS = 20;
    private static final int SUMMARY_MAX_CHARS = 2000;

    @PostConstruct
    public void register() {
        toolRegistry.register(Tool.builder()
                .name("delegate_task")
                .description("""
                        把一个独立子任务交给隔离的子 Agent 完成。
                        子 Agent 拿到的只有你提供的 goal + context，没有当前对话历史，
                        默认可调用 read_file / list_files / read_package / write_file / exec_command / web_search / web_extract / http_get。
                        可产出文件（写到 workspace 目录），完成后只返回一段不超过 2000 字的摘要给你，不污染主上下文。
                        适合：调研一个独立问题、读取并总结资料、并行收集多个独立信息源、生成一个独立的文件产出物（如某个模块代码、某份文档）。
                        不适合：不可逆的对外操作（发布、发送外部请求）、需要继续与用户交互或确认的任务。
                        """)
                .parameters(buildSchema())
                .handler(this::handle)
                .build());
    }

    private Map<String, Object> buildSchema() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("goal", Map.of(
                "type", "string",
                "description", "子任务目标，一句话描述要解决什么问题",
                "required", true
        ));
        params.put("context", Map.of(
                "type", "string",
                "description", "子 Agent 需要的全部背景信息（事实、文件路径、URL等），它没有对话历史。"
        ));
        params.put("allowed_tools", Map.of(
                "type", "string",
                "description", "可选 JSON 数组字符串，覆盖子 Agent 工具集合，例如 [\"read_file\",\"list_files\"]"
        ));
        return params;
    }

    @SuppressWarnings("unchecked")
    private String handle(String json) {
        try {
            Map<String, Object> args = MAPPER.readValue(json == null ? "{}" : json, Map.class);
            String goal = String.valueOf(args.getOrDefault("goal", "")).trim();
            if (goal.isEmpty()) return error("goal 不能为空");
            String ctx = String.valueOf(args.getOrDefault("context", "")).trim();
            List<String> tools = parseTools(args.get("allowed_tools"));

            String systemPrompt = """
                    你是一个被主 Agent 派发的子 Agent。
                    - 你只看到本任务的 goal 和 context，不知道主对话历史。
                    - 需要产出文件时直接用 write_file 写到 workspace 目录。
                    - 完成任务后给主 Agent 一段 ≤ 2000 字的摘要，包含：你做了什么、关键发现、产出的文件路径、引用的事实、是否成功。
                    - 不要重复执行同样的工具调用。不要做不可逆的对外操作（发布、发送外部请求）。
                    - 没把握时直接说"信息不足"，不要编造。
                    """;

            String userMessage = """
                    【子任务目标】
                    %s

                    【背景信息】
                    %s
                    """.formatted(goal, ctx.isEmpty() ? "（无）" : ctx);

            log.info("delegate_task 启动: goal='{}', allowedTools={}", truncate(goal, 80), tools);

            // 子 Agent：fresh context、受限工具
            String answer;
            try {
                answer = agentLoop.run(chatModel, systemPrompt, userMessage,
                        java.util.List.of(), SUBAGENT_MAX_ITERATIONS, null,
                        new com.miniagent.agent.intent.TaskPlan(
                                com.miniagent.agent.intent.IntentType.NEW_TASK,
                                goal, true, false, !tools.isEmpty(), tools,
                                java.util.List.of(),
                                "subagent")
                );
            } catch (Exception e) {
                return error("子 Agent 执行失败: " + e.getMessage());
            }

            String summary = clamp(answer, SUMMARY_MAX_CHARS);
            return MAPPER.writeValueAsString(Map.of(
                    "success", true,
                    "goal", goal,
                    "summary", summary
            ));
        } catch (Exception e) {
            log.error("delegate_task 工具执行失败", e);
            return error("delegate_task 工具执行失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseTools(Object raw) {
        if (raw == null) return DEFAULT_SUBAGENT_TOOLS;
        try {
            if (raw instanceof List<?> list) return ((List<String>) list);
            String s = String.valueOf(raw).trim();
            if (s.isEmpty()) return DEFAULT_SUBAGENT_TOOLS;
            return MAPPER.readValue(s, List.class);
        } catch (Exception e) {
            return DEFAULT_SUBAGENT_TOOLS;
        }
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n…(摘要已截断)";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String error(String msg) {
        try {
            return MAPPER.writeValueAsString(Map.of("success", false, "error", msg));
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + msg.replace("\"", "'") + "\"}";
        }
    }
}
