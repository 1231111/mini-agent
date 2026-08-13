package com.miniagent.agent.context;

import com.miniagent.agent.intent.IntentType;
import com.miniagent.agent.intent.TaskPlan;
import com.miniagent.common.RunStatus;
import com.miniagent.agent.skill.SkillStore;
import com.miniagent.agent.todo.TaskTodoStore;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.application.PromptTemplates;
import com.miniagent.memory.MemoryManager;
import com.miniagent.memory.MemoryStore;
import com.miniagent.memory.model.AgentContext;
import com.miniagent.memory.model.MemoryContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 上下文加载器：每次调模型前按意图裁剪并加载上下文。
 * 无会话级可变字段；todo/memory 仍读共享存储（多副本一致性见 TaskTodoStore / MemoryStore）。
 */
@Component
@Slf4j
public class ContextLoader {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MemoryStore memoryStore;
    @Autowired(required = false)
    private MemoryManager memoryManager;
    @Autowired
    private SkillStore skillStore;
    @Autowired
    private TaskTodoStore taskTodoStore;
    @Autowired
    private ToolRegistry toolRegistry;
    @Autowired(required = false)
    private TraceRecorder traceRecorder;
    @Autowired
    private ContextHistorySelector historySelector;

    @Value("${agent.context.history.question:0}")
    private int historyQuestion;
    @Value("${agent.context.history.question-with-ref:6}")
    private int historyQuestionWithRef;
    @Value("${agent.context.history.ref-scan-max:48}")
    private int historyRefScanMax;
    @Value("${agent.context.history.ref-pronoun-anchor:4}")
    private int historyRefPronounAnchor;
    @Value("${agent.context.history.review:4}")
    private int historyReview;
    @Value("${agent.context.history.new-task:6}")
    private int historyNewTask;
    @Value("${agent.context.history.continue:-1}")
    private int historyContinue;
    @Value("${agent.context.history.image:-1}")
    private int historyImage;
    @Value("${agent.context.history.default:-1}")
    private int historyDefault;

    public LoadedContext load(String sessionId, String query, TaskPlan taskPlan, List<ChatMessage> memMsgs) {
        IntentType intent = Objects.isNull(taskPlan) ? null : taskPlan.intent();
        ContextReferenceDecision ref = ContextReference.detect(query);
        ContextIntentPolicy policy = resolvePolicy(intent, ref);

        boolean suspended = false;
        boolean resumed = false;
        if (Objects.nonNull(sessionId)) {
            if (policy.resumeSuspendedTodo()) {
                resumed = taskTodoStore.resumeSuspended(sessionId);
            }
            if (policy.suspendActiveTodo()) {
                suspended = taskTodoStore.suspendActive(sessionId);
            }
        }

        List<ChatMessage> history = selectHistory(sessionId, memMsgs, query, policy, ref);
        Set<String> toolsForPrompt = toolsForPrompt(taskPlan);
        String systemPrompt = buildSystemPrompt(sessionId, query, policy, toolsForPrompt, ref);

        Map<String, Object> info = new HashMap<>();
        info.put("intent", Objects.isNull(intent) ? "null" : String.valueOf(intent));
        info.put("hasReference", ref.hasReference());
        info.put("referenceNegated", ref.isNegated());
        info.put("referenceConfidence", ref.confidence());
        info.put("referenceCandidates", ref.candidates());
        info.put("historyMessages", history.size());
        info.put("historyMax", policy.historyMaxMessages());
        info.put("injectTodo", policy.injectTodo());
        info.put("injectMidterm", policy.injectMidterm());
        info.put("injectMemory", policy.injectMemory());
        info.put("injectUser", policy.injectUser());
        info.put("injectSkills", policy.injectSkills());
        info.put("todoSuspended", suspended);
        info.put("todoResumed", resumed);
        info.put("toolGuidanceCount", toolsForPrompt.size());

        if (Objects.nonNull(traceRecorder) && Objects.nonNull(sessionId)) {
            try {
                traceRecorder.recordNode(sessionId, 0, "CONTEXT_LOAD",
                        JSON.writeValueAsString(info), RunStatus.SUCCESS.name(), 0);
            } catch (Exception e) {
                log.debug("CONTEXT_LOAD trace skip: {}", e.getMessage());
            }
        }

        log.info("ContextLoader: intent={}, ref={}/neg={}/conf={}, history={}/{}, todoInject={}, suspended={}, resumed={}",
                info.get("intent"), ref.hasReference(), ref.isNegated(), ref.confidence(),
                history.size(), policy.historyMaxMessages(),
                policy.injectTodo(), suspended, resumed);

        return new LoadedContext(systemPrompt, history, policy, info);
    }

    /** 基线策略 + 配置条数 + QUESTION 指代补历史 */
    ContextIntentPolicy resolvePolicy(IntentType intent, ContextReferenceDecision ref) {
        ContextIntentPolicy base = ContextIntentPolicy.forIntent(intent);
        IntentType t = intent == null ? IntentType.NEW_TASK : intent;
        boolean loadRef = ref != null && ref.shouldLoadPriorHistory();
        int hist = switch (t) {
            case QUESTION -> loadRef ? historyQuestionWithRef : historyQuestion;
            case REVIEW -> historyReview;
            case NEW_TASK -> historyNewTask;
            case CONTINUE_TASK -> historyContinue;
            case IMAGE_GENERATION -> historyImage;
            case HISTORY_REFERENCE -> historyQuestionWithRef;
            default -> historyDefault;
        };
        ContextIntentPolicy p = base.withHistoryMaxMessages(hist);
        if (t == IntentType.QUESTION && loadRef) {
            // 指代追问：相关历史精捞 + 记忆/中期，仍不注入旧 todo
            p = p.withInjectMemory(true).withInjectMidterm(true);
        }
        return p;
    }

    private List<ChatMessage> selectHistory(String sessionId, List<ChatMessage> all, String query,
                                            ContextIntentPolicy policy, ContextReferenceDecision ref) {
        int maxKeep = policy.historyMaxMessages();
        if (Objects.isNull(all) || all.isEmpty() || maxKeep == 0) {
            return List.of();
        }
        // QUESTION 指代：持久化向量检索 / 扫描窗精捞，禁止盲目最近 N
        if (ref != null && ref.shouldLoadPriorHistory() && maxKeep > 0) {
            return historySelector.selectRelevant(
                    sessionId, all, query, ref, maxKeep, historyRefScanMax, historyRefPronounAnchor);
        }
        if (maxKeep < 0 || maxKeep >= all.size()) {
            return new ArrayList<>(all);
        }
        int start = all.size() - maxKeep;
        while (start < all.size() && !(all.get(start) instanceof UserMessage)) {
            start++;
        }
        if (start >= all.size()) {
            start = all.size() - maxKeep;
        }
        return new ArrayList<>(all.subList(start, all.size()));
    }

    private Set<String> toolsForPrompt(TaskPlan taskPlan) {
        Set<String> registered = getAvailableToolNames();
        if (Objects.isNull(taskPlan) || Objects.isNull(taskPlan.allowedTools())) {
            return registered;
        }
        Set<String> allowed = new LinkedHashSet<>(taskPlan.allowedTools());
        Set<String> out = new LinkedHashSet<>();
        for (String n : registered) {
            if (allowed.contains(n)) {
                out.add(n);
            }
        }
        return out;
    }

    private String buildSystemPrompt(String sessionId, String currentQuery,
                                     ContextIntentPolicy policy, Set<String> toolNames,
                                     ContextReferenceDecision ref) {
        List<String> parts = new ArrayList<>();
        parts.add(PromptTemplates.identity());
        parts.add(PromptTemplates.AUTHORITY);
        if (ref != null && ref.shouldLoadPriorHistory()) {
            parts.add("以下消息中可能夹带按相关度检索到的历史片段，仅供指代消解参考；"
                    + "若与当前问题无关请忽略，勿编造未出现的报告/数据。");
        }

        String memoryBlock = memoryStore.getSnapshotForQuery(
                currentQuery,
                policy.injectMemory(),
                policy.injectUser(),
                policy.injectMidterm(),
                policy.userMaxChars());
        if (StringUtils.isNotBlank(memoryBlock)) {
            parts.add(memoryBlock);
        }

        // 注入 Agent 记忆系统上下文（情景记忆、语义事实、SOP、工作记忆）
        if (memoryManager != null && Objects.nonNull(sessionId)) {
            try {
                AgentContext agentCtx = new AgentContext();
                agentCtx.setTenantId("default");
                agentCtx.setUserId(String.valueOf(MemoryStore.getCurrentUser()));
                agentCtx.setSessionId(sessionId);
                agentCtx.setGoal(currentQuery);
                MemoryContext memCtx = memoryManager.buildContext(agentCtx);
                if (memCtx != null && !memCtx.isEmpty()) {
                    parts.add(buildAgentMemoryBlock(memCtx));
                }
            } catch (Exception e) {
                log.debug("Agent 记忆注入失败: {}", e.getMessage());
            }
        }

        if (policy.injectSkills()) {
            String skillSummary = skillStore.getSkillListSummary();
            if (StringUtils.isNotBlank(skillSummary)) {
                parts.add(skillSummary);
            }
        }

        parts.add(PromptTemplates.REASONING);
        parts.add(PromptTemplates.COMPLETION);

        if (toolNames.contains("read_file")) {
            parts.add(PromptTemplates.FILE_GUIDANCE);
        }
        if (toolNames.contains("search_code") || toolNames.contains("edit_file")) {
            parts.add(PromptTemplates.CODE_TOOLS_GUIDANCE);
        }
        if (toolNames.contains("delegate_task")) {
            parts.add(PromptTemplates.REASONING_STRATEGY);
        }
        if (!toolNames.isEmpty()) {
            parts.add(PromptTemplates.BEHAVIOR);
        }
        if (toolNames.contains("browser_navigate")) {
            parts.add(PromptTemplates.BROWSER_GUIDANCE);
        }
        if (toolNames.contains("web_search")) {
            parts.add(PromptTemplates.WEB_SEARCH_GUIDANCE);
        }
        if (toolNames.contains("comfyui_status")) {
            parts.add(PromptTemplates.COMFYUI_GUIDANCE);
        }
        if (toolNames.contains("image_generate") && !toolNames.contains("comfyui_status")) {
            parts.add(PromptTemplates.IMAGE_GENERATE_GUIDANCE);
        }
        if (toolNames.contains("memory")) {
            parts.add(PromptTemplates.MEMORY_GUIDANCE);
        }
        if (toolNames.contains("todo") || toolNames.contains("delegate_task")) {
            parts.add(PromptTemplates.PLANNING_GUIDANCE);
        }
        if (toolNames.contains("delegate_task")) {
            parts.add(PromptTemplates.ROLE_DELEGATION_GUIDANCE);
        }
        if (toolNames.contains("write_file") && toolNames.contains("exec_command")) {
            parts.add(PromptTemplates.VERIFICATION_GUIDANCE);
        }

        if (policy.injectTodo() && Objects.nonNull(sessionId)) {
            String todoBlock = taskTodoStore.render(sessionId);
            if (StringUtils.isNotBlank(todoBlock)) {
                parts.add(todoBlock);
            }
        }

        parts.add(PromptTemplates.CONFIRMATION);
        parts.add(PromptTemplates.OUTPUT);
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
        parts.add("当前时间：" + now);

        return String.join("\n\n", parts);
    }

    private Set<String> getAvailableToolNames() {
        try {
            return new HashSet<>(toolRegistry.getToolNames());
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    /** 将 Agent 记忆上下文格式化为 system prompt 片段 */
    private String buildAgentMemoryBlock(MemoryContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Agent 记忆\n");

        if (ctx.getWorkingMemory() != null && ctx.getWorkingMemory().getGoal() != null) {
            sb.append("当前任务: ").append(ctx.getWorkingMemory().getGoal()).append("\n");
            if (ctx.getWorkingMemory().getCurrentTaskId() != null) {
                sb.append("当前步骤: ").append(ctx.getWorkingMemory().getCurrentTaskId()).append("\n");
            }
        }

        if (!ctx.getFacts().isEmpty()) {
            sb.append("\n已知事实:\n");
            for (String f : ctx.getFacts()) {
                sb.append("- ").append(f).append("\n");
            }
        }

        if (!ctx.getEpisodes().isEmpty()) {
            sb.append("\n历史经验:\n");
            for (var ep : ctx.getEpisodes()) {
                sb.append("- ").append(ep.getTaskSummary());
                if (ep.getResolution() != null) {
                    sb.append(" → ").append(ep.getResolution());
                }
                sb.append("\n");
            }
        }

        if (!ctx.getSkills().isEmpty()) {
            sb.append("\n可用方法:\n");
            for (String s : ctx.getSkills()) {
                sb.append("- ").append(s).append("\n");
            }
        }

        if (!ctx.getPreferences().isEmpty()) {
            sb.append("\n用户偏好:\n");
            for (String p : ctx.getPreferences()) {
                sb.append("- ").append(p).append("\n");
            }
        }

        return sb.toString().trim();
    }
}
