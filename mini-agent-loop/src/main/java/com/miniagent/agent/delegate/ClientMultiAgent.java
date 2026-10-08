package com.miniagent.agent.delegate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ExecutionProperties;
import com.miniagent.agent.core.SessionEventCenter;
import com.miniagent.agent.execution.ToolCallContext;
import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.task.TaskSignals;
import com.miniagent.agent.tool.CapabilityRegistry;
import dev.langchain4j.model.chat.ChatModel;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 客户端上的一次对话。主会话是宿主，角色子代理在这里跑，挂同一份取消和预算。
 */
@Component
public class ClientMultiAgent {

    private static final Logger log = LoggerFactory.getLogger(ClientMultiAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SUMMARY_MAX_CHARS = 2000;
    private static final String NESTED_DELEGATE = "delegate_task";
    private static final String DEFAULT_SYSTEM_PROMPT = """
            你是一个被主 Agent 派发的子 Agent。
            - 你只看到本任务的 goal 和 context，不知道主对话历史。
            - 需要产出文件时直接用 write_file 写到 workspace 目录。
            - 完成任务后给主 Agent 一段 ≤ 2000 字的摘要，包含：你做了什么、关键发现、产出的文件路径、引用的事实、是否成功。
            - 不要重复执行同样的工具调用。不要做不可逆的对外操作（发布、发送外部请求）。
            - 没把握时直接说"信息不足"，不要编造。
            """;

    private final ExecutionControl executionControl;
    private final RoleLoader roleLoader;
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    @Autowired
    private AgentLoop agentLoop;
    @Autowired
    private ChatModel chatModel;
    @Autowired
    private ExecutionProperties executionProperties;
    @Autowired
    private CapabilityRegistry capabilityRegistry;
    @Autowired(required = false)
    private SessionEventCenter eventCenter;

    public ClientMultiAgent(ExecutionControl executionControl, RoleLoader roleLoader) {
        this.executionControl = executionControl;
        this.roleLoader = roleLoader;
    }

    public void open(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            sessions.add(sessionId);
        }
    }

    public void close(String sessionId) {
        if (sessionId == null) {
            return;
        }
        sessions.remove(sessionId);
    }

    public boolean isOpen(String sessionId) {
        return sessionId != null && sessions.contains(sessionId);
    }

    public List<String> roleIds() {
        return roleLoader.getRoleIds();
    }

    public void attachWorker(String parentSessionId, String workerSessionId) {
        executionControl.attach(workerSessionId, parentSessionId);
    }

    public void detachWorker(String workerSessionId) {
        executionControl.detach(workerSessionId);
    }

    /** 在当前客户端会话下跑一个角色子代理，只把摘要交回主循环。 */
    public String runWorker(String goal, String roleId, String context, List<String> customTools) {
        if (StringUtils.isBlank(goal)) {
            return error("goal 不能为空");
        }
        String role = roleId == null ? "" : roleId.trim();
        if (role.isEmpty()) {
            role = RoleContext.getRole();
        }
        RoleConfig roleConfig = null;
        if (StringUtils.isNotBlank(role)) {
            roleConfig = roleLoader.getRole(role);
            if (roleConfig == null) {
                return error("未知角色: " + role + "。可用角色: "
                        + String.join(", ", roleLoader.getRoleIds()));
            }
        }
        String systemPrompt = roleConfig == null
                ? DEFAULT_SYSTEM_PROMPT
                : buildRoleSystemPrompt(roleConfig, goal);
        List<String> tools = resolveTools(customTools, roleConfig);
        String userMessage = """
                【子任务目标】
                %s

                【背景信息】
                %s
                """.formatted(goal, StringUtils.isBlank(context) ? "（无）" : context);
        String roleLabel = roleConfig == null ? "通用" : roleConfig.getName();
        String parentSid = AgentLoop.getCurrentSession();
        String subSid = StringUtils.isNotBlank(parentSid)
                ? parentSid + ":sub:" + Long.toHexString(System.nanoTime())
                : "sub_" + Long.toHexString(System.nanoTime());
        log.info("客户端子代理启动 role={} goal={} tools={}", roleLabel, truncate(goal, 80), tools);
        ChatModel model = Optional.ofNullable(AgentLoop.getCurrentChatModel()).orElse(chatModel);
        if (StringUtils.isNotBlank(parentSid)) {
            attachWorker(parentSid, subSid);
        }
        Consumer<String> progress = text -> publishProgress(parentSid, roleLabel, text);
        String answer;
        try (SubagentScope scope = SubagentScope.enter(subSid, role, false)) {
            answer = agentLoop.run(model, systemPrompt, userMessage, List.of(),
                    executionProperties.getSubagentMaxIterations(), progress,
                    new TaskPlan(goal, tools, List.of(),
                            "subagent:" + (role.isEmpty() ? "general" : role),
                            false, TaskSignals.NONE));
        } catch (Exception e) {
            return error("子 Agent 执行失败: " + e.getMessage());
        } finally {
            detachWorker(subSid);
        }
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "success", true,
                    "role", roleLabel,
                    "goal", goal,
                    "summary", clamp(answer, SUMMARY_MAX_CHARS)));
        } catch (Exception e) {
            return error(e.getMessage());
        }
    }

    private void publishProgress(String parentSid, String roleLabel, String text) {
        if (eventCenter == null || StringUtils.isBlank(parentSid) || StringUtils.isBlank(text)) {
            return;
        }
        eventCenter.publish(parentSid, "progress", "[" + roleLabel + "] " + text);
    }

    List<String> resolveTools(List<String> custom, RoleConfig roleConfig) {
        List<String> configured;
        if (roleConfig != null && roleConfig.getAllowedTools() != null
                && !roleConfig.getAllowedTools().isEmpty()) {
            configured = roleConfig.getAllowedTools();
        } else {
            configured = capabilityRegistry.toolsFor(CapabilityRegistry.GENERAL);
        }
        Set<String> ceiling = new LinkedHashSet<>(configured);
        Set<String> parent = ToolCallContext.allowedTools();
        if (parent != null) {
            ceiling.retainAll(parent);
        }
        List<String> wanted = custom != null && !custom.isEmpty() ? custom : configured;
        Set<String> out = new LinkedHashSet<>();
        for (String t : wanted) {
            if (t == null || t.isBlank() || NESTED_DELEGATE.equals(t)) {
                continue;
            }
            if (ceiling.contains(t) && capabilityRegistry.containsTool(t)) {
                out.add(t);
            }
        }
        return new ArrayList<>(out);
    }

    private static String buildRoleSystemPrompt(RoleConfig roleConfig, String goal) {
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

    private static String clamp(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n…(摘要已截断)";
    }

    private static String truncate(String s, int max) {
        return com.miniagent.common.StringUtils.truncate(s, max);
    }

    private static String error(String msg) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "success", false, "error", Objects.requireNonNullElse(msg, "")));
        } catch (Exception e) {
            return "{\"success\":false}";
        }
    }
}
