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
 * 子任务派发工具：给主 Agent 一个"分包"能力。
 * 支持角色化子Agent：tester/developer/pm/designer/security
 *
 * 特性：
 *   - 子 Agent 拿到 fresh context（只有 goal + context）
 *   - 受限工具集（根据角色或自定义配置）
 *   - 角色化系统提示词（专业领域指导）
 *   - 只把最终回答（< 2000 字）塞回主 Agent 的 tool 结果里
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
    @Autowired
    private RoleLoader roleLoader;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 子 Agent 默认可用的工具集合（读 + 调研 + 产出 + 生图）。 */
    private static final List<String> DEFAULT_SUBAGENT_TOOLS = List.of(
            "read_file", "list_files", "read_package", "write_file", "exec_command",
            "search_code", "edit_file", "ast_search", "codebase_search",
            "web_search", "web_extract", "http_get",
            "browser_navigate", "browser_snapshot", "browser_click",
            "browser_type", "browser_press", "browser_scroll",
            "browser_screenshot", "browser_evaluate", "browser_close",
            "image_generate"
    );

    private static final List<String> IMAGE_TOOLS_FOR_SUB = List.of(
            "image_generate", "comfyui_txt2img", "comfyui_img2img", "comfyui_check_quality"
    );

    private static final int SUBAGENT_MAX_ITERATIONS = 25;
    private static final int SUMMARY_MAX_CHARS = 2000;

    @PostConstruct
    public void register() {
        toolRegistry.register(Tool.builder()
                .name("delegate_task")
                .description("""
                        把一个独立子任务交给隔离的子 Agent 完成。
                        支持角色化子Agent，通过 role 参数指定角色：
                        - tester: 测试工程师，擅长功能验证、Bug发现、自动化测试
                        - developer: 开发工程师，擅长代码编写、Bug修复、功能实现
                        - pm: 产品经理，擅长需求分析、文档撰写、方案设计
                        - designer: UI设计师，擅长界面审查、视觉验证、交互优化
                        - security: 安全工程师，擅长安全测试、漏洞扫描、权限验证

                        子 Agent 拿到的只有你提供的 goal + context，没有当前对话历史。
                        可产出文件（写到 workspace 目录），完成后只返回一段不超过 2000 字的摘要给你，不污染主上下文。

                        适合：调研一个独立问题、读取并总结资料、并行收集多个独立信息源、生成一个独立的文件产出物。
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
        params.put("role", Map.of(
                "type", "string",
                "description", "子Agent角色：tester(测试)/developer(开发)/pm(产品)/designer(UI)/security(安全)。不指定则使用通用配置。"
        ));
        params.put("context", Map.of(
                "type", "string",
                "description", "子 Agent 需要的全部背景信息（事实、文件路径、URL等），它没有对话历史。"
        ));
        params.put("allowed_tools", Map.of(
                "type", "string",
                "description", "可选 JSON 数组字符串，覆盖子 Agent 工具集合（优先级高于角色配置），例如 [\"read_file\",\"list_files\"]"
        ));
        return params;
    }

    @SuppressWarnings("unchecked")
    private String handle(String json) {
        try {
            Map<String, Object> args = MAPPER.readValue(json == null ? "{}" : json, Map.class);
            String goal = String.valueOf(args.getOrDefault("goal", "")).trim();
            if (goal.isEmpty()) return error("goal 不能为空");

            String roleId = String.valueOf(args.getOrDefault("role", "")).trim();
            // 如果未指定角色，尝试从上下文获取
            if (roleId.isEmpty()) {
                roleId = RoleContext.getRole();
            }
            String ctx = String.valueOf(args.getOrDefault("context", "")).trim();
            List<String> customTools = parseTools(args.get("allowed_tools"));

            // 加载角色配置
            RoleConfig roleConfig = null;
            if (!roleId.isEmpty()) {
                roleConfig = roleLoader.getRole(roleId);
                if (roleConfig == null) {
                    return error("未知角色: " + roleId + "。可用角色: " + String.join(", ", roleLoader.getRoleIds()));
                }
            }

            // 确定系统提示词
            String systemPrompt;
            if (roleConfig != null) {
                systemPrompt = buildRoleSystemPrompt(roleConfig, goal);
            } else {
                systemPrompt = DEFAULT_SYSTEM_PROMPT;
            }

            // 确定工具集（自定义 > 角色配置 > 默认）
            List<String> tools;
            if (!customTools.isEmpty()) {
                tools = new java.util.ArrayList<>(customTools);
            } else if (roleConfig != null && roleConfig.getAllowedTools() != null && !roleConfig.getAllowedTools().isEmpty()) {
                tools = new java.util.ArrayList<>(roleConfig.getAllowedTools());
            } else {
                tools = new java.util.ArrayList<>(DEFAULT_SUBAGENT_TOOLS);
            }
            // 任务要求生图时自动补齐 image 工具（避免 developer 角色无 image_generate）
            if (needsImageTools(goal, ctx)) {
                for (String t : IMAGE_TOOLS_FOR_SUB) {
                    if (!tools.contains(t)) tools.add(t);
                }
            }

            String userMessage = """
                    【子任务目标】
                    %s

                    【背景信息】
                    %s
                    """.formatted(goal, ctx.isEmpty() ? "（无）" : ctx);

            // 强隔离：禁止嵌套派发，避免子 Agent 再开子 Agent 污染控制面
            tools.remove("delegate_task");

            String roleLabel = roleConfig != null ? roleConfig.getName() : "通用";
            log.info("delegate_task 启动: role='{}', goal='{}', allowedTools={}", roleLabel, truncate(goal, 80), tools);

            // 子 Agent：派生 sessionId + SubagentScope 完整沙箱（消息栈仍为 fresh List.of）
            String parentSid = AgentLoop.getCurrentSession();
            String subSid = (parentSid != null && !parentSid.isBlank())
                    ? parentSid + ":sub:" + Long.toHexString(System.nanoTime())
                    : "sub_" + Long.toHexString(System.nanoTime());

            ChatModel modelForSub = AgentLoop.getCurrentChatModel() != null
                    ? AgentLoop.getCurrentChatModel() : chatModel;

            String answer;
            try (SubagentScope scope = SubagentScope.enter(subSid, roleId, false)) {
                answer = agentLoop.run(modelForSub, systemPrompt, userMessage,
                        java.util.List.of(), /* 不继承父对话脏历史 */ SUBAGENT_MAX_ITERATIONS, null,
                        new com.miniagent.agent.intent.TaskPlan(
                                com.miniagent.agent.intent.IntentType.NEW_TASK,
                                goal, true, false, !tools.isEmpty(), tools,
                                java.util.List.of(),
                                "subagent:" + (roleId.isEmpty() ? "general" : roleId))
                );
            } catch (Exception e) {
                return error("子 Agent 执行失败: " + e.getMessage());
            }

            String summary = clamp(answer, SUMMARY_MAX_CHARS);
            return MAPPER.writeValueAsString(Map.of(
                    "success", true,
                    "role", roleLabel,
                    "goal", goal,
                    "summary", summary
            ));
        } catch (Exception e) {
            log.error("delegate_task 工具执行失败", e);
            return error("delegate_task 工具执行失败: " + e.getMessage());
        }
    }

    /**
     * 构建角色化系统提示词
     */
    private String buildRoleSystemPrompt(RoleConfig roleConfig, String goal) {
        return """
                %s

                ## 当前任务
                你正在执行以下任务：
                %s

                ## 工作要求
                1. 严格按照你的角色职责和工作流程执行任务
                2. 产出的文件写到 workspace 目录
                3. 完成任务后给主 Agent 一段 ≤ 2000 字的摘要
                4. 摘要包含：你做了什么、关键发现、产出的文件路径、是否成功
                5. 不要重复执行同样的工具调用
                6. 不要做不可逆的对外操作
                7. 没把握时直接说"信息不足"，不要编造
                """.formatted(roleConfig.getSystemPrompt(), goal);
    }

    /** 默认系统提示词（无角色时使用） */
    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个被主 Agent 派发的子 Agent。
            - 你只看到本任务的 goal 和 context，不知道主对话历史。
            - 需要产出文件时直接用 write_file 写到 workspace 目录。
            - 完成任务后给主 Agent 一段 ≤ 2000 字的摘要，包含：你做了什么、关键发现、产出的文件路径、引用的事实、是否成功。
            - 不要重复执行同样的工具调用。不要做不可逆的对外操作（发布、发送外部请求）。
            - 没把握时直接说"信息不足"，不要编造。
            """;

    private static boolean needsImageTools(String goal, String ctx) {
        String blob = ((goal == null ? "" : goal) + " " + (ctx == null ? "" : ctx)).toLowerCase();
        return blob.contains("image_generate")
                || blob.contains("生图")
                || blob.contains("生成图片")
                || blob.contains("生成图")
                || blob.contains("结构图")
                || blob.contains("架构图")
                || blob.contains("流程图")
                || blob.contains("文生图")
                || blob.contains("txt2img")
                || blob.contains("img2img")
                || blob.contains("diagram")
                || (blob.contains("水平布局") && blob.contains("图"));
    }

    @SuppressWarnings("unchecked")
    private List<String> parseTools(Object raw) {
        if (raw == null) return List.of();
        try {
            if (raw instanceof List<?> list) return ((List<String>) list);
            String s = String.valueOf(raw).trim();
            if (s.isEmpty()) return List.of();
            return MAPPER.readValue(s, List.class);
        } catch (Exception e) {
            return List.of();
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
