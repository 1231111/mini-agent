package com.miniagent.agent.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.Tool;
import com.miniagent.agent.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

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
    private RoleLoader roleLoader;
    @Autowired
    private ClientMultiAgent clientMultiAgent;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostConstruct
    public void register() {
        toolRegistry.register(Tool.builder()
                .name("delegate_task")
                .description(buildDescription())
                .parameters(buildSchema())
                .handler(this::handle)
                .build());
    }

    /**
     * 工具描述按 roles.yml 的实际角色表动态生成。
     * 角色清单的唯一真实来源是配置文件——此处不再手写，避免新增角色后模型仍然看不到。
     */
    private String buildDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append("把一个独立子任务交给隔离的子 Agent 完成。\n\n");

        List<RoleConfig> roles = roleLoader.getAllRoles();
        if (Objects.nonNull(roles) && !roles.isEmpty()) {
            sb.append("支持角色化子 Agent，用 role 参数指定（不指定则使用通用配置）：\n");
            for (RoleConfig role : roles) {
                if (Objects.isNull(role) || StringUtils.isBlank(role.getId())) {
                    continue;
                }
                sb.append("- ").append(role.getId());
                if (StringUtils.isNotBlank(role.getName())) {
                    sb.append("（").append(role.getName()).append("）");
                }
                if (StringUtils.isNotBlank(role.getDescription())) {
                    sb.append("：").append(role.getDescription());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("子 Agent 拿到的只有你提供的 goal + context，没有当前对话历史。\n");
        sb.append("可产出文件（写到 workspace 目录），完成后只返回一段不超过 2000 字的摘要给你，不污染主上下文。\n\n");
        sb.append("适合：调研一个独立问题、读取并总结资料、并行收集多个独立信息源、生成一个独立的文件产出物。\n");
        sb.append("不适合：不可逆的对外操作（发布、发送外部请求）、需要继续与用户交互或确认的任务。");
        return sb.toString();
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
                "description", "可选 JSON 数组字符串，只能在父任务与角色均允许的工具中收窄，"
                        + "例如 [\"read_file\",\"list_files\"]"
        ));
        return params;
    }

    @SuppressWarnings("unchecked")
    private String handle(String json) {
        try {
            Map<String, Object> args = MAPPER.readValue(Optional.ofNullable(json).orElse("{}"), Map.class);
            String goal = String.valueOf(args.getOrDefault("goal", "")).trim();
            if (goal.isEmpty()) {
                return error("goal 不能为空");
            }

            String roleId = String.valueOf(args.getOrDefault("role", "")).trim();
            String ctx = String.valueOf(args.getOrDefault("context", "")).trim();
            return clientMultiAgent.runWorker(goal, roleId, ctx, parseTools(args.get("allowed_tools")));
        } catch (Exception e) {
            log.error("delegate_task 工具执行失败", e);
            return error("delegate_task 工具执行失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseTools(Object raw) {
        if (Objects.isNull(raw)) {
            return List.of();
        }
        try {
            if (raw instanceof List<?> list) {
                return ((List<String>) list);
            }
            String s = String.valueOf(raw).trim();
            if (s.isEmpty()) {
                return List.of();
            }
            return MAPPER.readValue(s, List.class);
        } catch (Exception e) {
            return List.of();
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
