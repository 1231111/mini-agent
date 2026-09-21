package com.miniagent.agent.memory.lifecycle;

import com.miniagent.common.SecurityUtils;
import com.miniagent.common.StringUtils;
import com.miniagent.common.model.EffectiveModelContext;
import com.miniagent.config.OpenAiModelProperties;
import com.miniagent.config.model.UserModelBinder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.entity.AgentEventEntity;
import com.miniagent.agent.memory.entity.AgentEpisodeEntity;
import com.miniagent.agent.memory.repository.AgentEventRepository;
import com.miniagent.agent.memory.repository.AgentEpisodeRepository;
import com.miniagent.memory.MemoryKeys;
import com.miniagent.memory.MemoryManager;
import com.miniagent.memory.MemoryStore;
import com.miniagent.memory.lifecycle.ConsolidationService;
import com.miniagent.memory.model.MemoryScope;
import com.miniagent.memory.model.SemanticFact;
import com.miniagent.memory.model.WorkingMemory;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 默认巩固服务：session 结束时从事件流 + 工作记忆提炼 Episode。
 *
 * 流程：
 * 1. 读取该 session 的工作记忆（goal / 已完成 / 失败 / 产物）
 * 2. 查找该 session 所有未处理的事件
 * 3. 调 LLM 提炼为 Episode，再用工作记忆覆盖/补强（防止 LLM 反推偏离真实目标）
 * 4. 保存 Episode，标记事件为已处理
 *
 * 为什么要读工作记忆：goal 由 planner 明确定义、completedTasks 是执行事实，
 * 都比 LLM 从事件流反推的摘要可靠。且工作记忆挂在 Redis TTL 上，到期即蒸发，
 * 不在这里结转就等于永久丢失。
 */
@Service
public class DefaultConsolidationService implements ConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultConsolidationService.class);
    private static final int FACT_SUBJECT_MAX = 256;
    private static final int FACT_OBJECT_MAX = 512;

    @Autowired
    private AgentEventRepository eventRepository;

    @Autowired
    private AgentEpisodeRepository episodeRepository;

    @Autowired
    private WorkingMemoryManager workingMemoryManager;

    @Autowired(required = false)
    private ChatModel chatModel;

    @Autowired(required = false)
    private OpenAiModelProperties openAiModelProperties;

    /**
     * 按会话反查归属用户并绑定其模型。巩固可能跑在 {@code @Scheduled} 线程或
     * {@code CompletableFuture} 上，两种情况都没有用户上下文，ThreadLocal 也不继承 ——
     * 不显式绑定就会静默回退到全局 Bean，表现为「主对话正常、后台任务 401」。
     */
    @Autowired(required = false)
    private UserModelBinder userModelBinder;

    @Autowired(required = false)
    @Lazy
    private MemoryManager memoryManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    @Transactional
    public void consolidate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        // 巩固的辅助 LLM 调用必须走该会话所属用户配置的模型：本方法既可能被交互线程的
        // CompletableFuture 调到，也可能被定时 Worker 调到，两者都不保证线程上已有模型绑定。
        try (var ignoredModel = bindSessionModel(sessionId)) {
            doConsolidate(sessionId);
        }
    }

    /** 交互线程已绑过则不重复解析；解析不到归属用户时返回空绑定，随后回退全局模型。 */
    private UserModelBinder.Binding bindSessionModel(String sessionId) {
        if (userModelBinder == null || EffectiveModelContext.isBound()) {
            return UserModelBinder.Binding.noop();
        }
        return userModelBinder.bindForSession(sessionId);
    }

    private void doConsolidate(String sessionId) {
        // 1. 先读工作记忆：它挂在 Redis TTL 上，是本流程里最易失的数据，优先抢救
        WorkingMemory workingMemory = loadWorkingMemory(sessionId);

        List<AgentEventEntity> events = eventRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<AgentEventEntity> unprocessed = events.stream()
            .filter(e -> !Boolean.TRUE.equals(e.getProcessed()))
            .collect(Collectors.toList());

        if (unprocessed.isEmpty()) {
            // 事件已全部处理过。若工作记忆仍有实质任务状态、且该 session 尚无 Episode，
            // 补一次纯状态结转 —— 否则任务目标会随 Redis TTL 一起消失。
            // hasEpisode 用于防重：定时 Worker 反复调用也不会写出重复 Episode。
            if (hasSubstance(workingMemory) && !hasEpisode(sessionId)) {
                try {
                    AgentEpisodeEntity carried = extractEpisode(List.of(), workingMemory);
                    if (carried != null) {
                        episodeRepository.save(carried);
                        workingMemoryManager.touch(sessionId);
                        log.info("工作记忆结转: session={}, goal={}", sessionId, carried.getTaskSummary());
                    }
                } catch (Exception e) {
                    log.error("工作记忆结转失败: session={}", sessionId, e);
                }
            }
            return;
        }

        try {
            // 2. 提炼 Episode（事件流 + 工作记忆联合）
            AgentEpisodeEntity episode = extractEpisode(unprocessed, workingMemory);
            if (episode != null) {
                episodeRepository.save(episode);
                workingMemoryManager.touch(sessionId);
                log.info("巩固完成: session={}, episode={}", sessionId, episode.getTaskSummary());
            }

            // 3. 标记为已处理
            List<Long> ids = unprocessed.stream().map(AgentEventEntity::getId).collect(Collectors.toList());
            eventRepository.markProcessed(ids);
        } catch (Exception e) {
            log.error("巩固失败: session={}", sessionId, e);
        }
    }

    /** 读工作记忆；失败不影响主流程（Redis 不可用时退化为无工作记忆）。 */
    private WorkingMemory loadWorkingMemory(String sessionId) {
        try {
            return workingMemoryManager.get(sessionId);
        } catch (Exception e) {
            log.debug("读取工作记忆失败: session={}, {}", sessionId, e.getMessage());
            return null;
        }
    }

    /** 工作记忆是否有值得结转的实质内容。 */
    private boolean hasSubstance(WorkingMemory wm) {
        if (wm == null) {
            return false;
        }
        boolean hasGoal = wm.getGoal() != null && !wm.getGoal().isBlank();
        boolean hasDone = wm.getCompletedTasks() != null && !wm.getCompletedTasks().isEmpty();
        boolean hasFailed = wm.getFailedTasks() != null && !wm.getFailedTasks().isEmpty();
        return hasGoal || hasDone || hasFailed;
    }

    /** 该 session 是否已有 Episode（防止纯状态结转重复执行）。 */
    private boolean hasEpisode(String sessionId) {
        try {
            List<AgentEpisodeEntity> existing = episodeRepository.findBySessionId(sessionId);
            return existing != null && !existing.isEmpty();
        } catch (Exception e) {
            log.debug("查询已有 Episode 失败，保守认为已存在: session={}", sessionId);
            return true;
        }
    }

    private AgentEpisodeEntity extractEpisode(List<AgentEventEntity> events, WorkingMemory wm) {
        if (events.isEmpty() && !hasSubstance(wm)) {
            return null;
        }

        // 构建事件摘要
        StringBuilder eventSummary = new StringBuilder();
        for (AgentEventEntity event : events) {
            eventSummary.append("[").append(event.getEventType()).append("]");
            if (event.getStatus() != null) {
                eventSummary.append(" ").append(event.getStatus());
            }
            eventSummary.append(": ").append(truncate(event.getPayloadJson(), 200));
            eventSummary.append("\n");
        }

        // 工作记忆里的成败信号也计入：它是执行事实，不依赖事件流是否完整
        boolean wmHasFailure = wm != null
            && wm.getFailedTasks() != null && !wm.getFailedTasks().isEmpty();
        boolean wmHasProgress = wm != null
            && wm.getCompletedTasks() != null && !wm.getCompletedTasks().isEmpty();

        boolean hasFailure = wmHasFailure || events.stream()
            .anyMatch(e -> e.getStatus() == AgentEventEntity.EventStatus.FAILED
                || e.getEventType() == AgentEventEntity.EventType.ERROR
                || e.getEventType() == AgentEventEntity.EventType.TASK_FAIL);

        boolean hasSuccess = events.stream()
            .anyMatch(e -> e.getEventType() == AgentEventEntity.EventType.TASK_COMPLETE)
            || (wmHasProgress && !wmHasFailure);

        AgentEpisodeEntity.Outcome outcome;
        if (hasSuccess && !hasFailure) {
            outcome = AgentEpisodeEntity.Outcome.SUCCESS;
        }
        else if (hasFailure && !hasSuccess) {
            outcome = AgentEpisodeEntity.Outcome.FAILURE;
        } else {
            outcome = AgentEpisodeEntity.Outcome.PARTIAL;
        }

        // 调 LLM 提炼（如果有）
        String taskSummary = null;
        String resolution = null;
        List<String> actions = new ArrayList<>();
        List<String> observations = new ArrayList<>();
        List<String> promotedFacts = new ArrayList<>();
        boolean llmSkipped = false;

        // 无事件流时不调 LLM：没有可提炼的原料。
        // 模型取本轮生效的那套（通常是会话所属用户配置的），全局 Bean 仅作未绑定时的兜底。
        ChatModel model = EffectiveModelContext.chatOr(chatModel);
        if (model != null && !events.isEmpty()) {
            try {
                Map<String, String> extracted = llmExtract(eventSummary.toString(), outcome.name(), model);
                if ("true".equalsIgnoreCase(extracted.get("skip"))) {
                    // 语义闸门：模型判定事件流无可复用价值，退化为纯工作记忆结转
                    log.debug("语义闸门：事件流无可复用价值，退化为工作记忆结转");
                    llmSkipped = true;
                } else {
                    taskSummary = extracted.get("task");
                    resolution = extracted.get("resolution");
                    String actionsStr = extracted.get("actions");
                    if (actionsStr != null) {
                        actions = Arrays.stream(actionsStr.split(";"))
                            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                    }
                    String obsStr = extracted.get("observations");
                    if (obsStr != null) {
                        observations = Arrays.stream(obsStr.split(";"))
                            .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                    }
                    promotedFacts.addAll(splitFacts(extracted.get("facts")));
                }
            } catch (Exception e) {
                // 必须如实说明这次用的是哪套模型：写死全局配置会把排查引到错误方向
                String modelSource = EffectiveModelContext.isBound()
                        ? "modelSource=user-config"
                        : "modelSource=global-fallback, "
                            + (openAiModelProperties == null
                                ? "bean=langchain4j.open-ai.chat-model"
                                : openAiModelProperties.describeChatClient());
                log.warn("LLM 提炼 Episode 失败: purpose=episode-consolidate, {}, err={}",
                        modelSource, SecurityUtils.redactSensitive(e.getMessage()));
            }
        }

        // 回退：从事件构建
        if (taskSummary == null) {
            taskSummary = buildFallbackSummary(events);
        }

        // ===== 工作记忆结转：覆盖 / 补强 LLM 反推结果 =====
        // goal 由 planner 明确定义，比 LLM 从事件流反推的摘要可靠，优先级最高。
        if (wm != null && wm.getGoal() != null && !wm.getGoal().isBlank()) {
            taskSummary = wm.getGoal();
        } else if (events.isEmpty()) {
            taskSummary = buildWorkingMemorySummary(wm);
        }

        // 已完成任务并入 actions
        if (wmHasProgress) {
            for (String task : wm.getCompletedTasks()) {
                if (task != null && !task.isBlank() && !actions.contains(task)) {
                    actions.add(task);
                }
            }
        }

        // 未解决的任务写入 resolution —— 这是代码里查不到的负面知识，复用价值最高
        if (wmHasFailure) {
            String unresolved = "未解决: " + String.join("; ", wm.getFailedTasks());
            resolution = (resolution == null || resolution.isBlank())
                ? unresolved
                : resolution + " | " + unresolved;
        }

        // 约束与产物并入 observations，避免只留在易失的工作记忆里
        String contextNote = buildContextNote(wm);
        if (!contextNote.isEmpty()) {
            observations.add(contextNote);
        }
        if (actions.isEmpty()) {
            actions = events.stream()
                .filter(e -> e.getEventType() == AgentEventEntity.EventType.TOOL_EXECUTION)
                .map(e -> {
                    try {
                        Map<String, Object> payload = objectMapper.readValue(e.getPayloadJson(),
                            new TypeReference<>() {});
                        return (String) payload.getOrDefault("tool", "unknown");
                    } catch (Exception ex) {
                        return "unknown";
                    }
                })
                .distinct()
                .collect(Collectors.toList());
        }

        // 语义闸门判定无价值、且工作记忆也没有可结转内容 → 整条 Episode 不值得写
        if (llmSkipped && !hasSubstance(wm)) {
            log.debug("语义闸门：事件流无可复用价值且无工作记忆状态，放弃 Episode");
            return null;
        }

        // 构建 Episode
        AgentEpisodeEntity episode = new AgentEpisodeEntity();
        // 纯状态结转时没有事件，租户/会话从工作记忆取
        episode.setTenantId(!events.isEmpty() ? events.get(0).getTenantId() : wm.getTenantId());
        episode.setSessionId(!events.isEmpty() ? events.get(0).getSessionId() : wm.getSessionId());
        if (wm != null) {
            episode.setProjectId(wm.getProjectId());
        }
        episode.setUserId(resolveEpisodeUserId(wm));
        episode.setTaskSummary(truncate(taskSummary, 500));
        episode.setOutcome(outcome);
        episode.setActionsJson(toJson(actions));
        episode.setObservationsJson(toJson(observations));
        episode.setResolution(resolution);
        // 有明确 goal 的任务，检索价值高于"从事件流猜出来的"，抬高其重要性下限
        double importance = hasFailure ? 0.8 : 0.5;
        if (wm != null && wm.getGoal() != null && !wm.getGoal().isBlank()) {
            importance = Math.max(importance, 0.6);
        }
        episode.setImportance(importance);

        promoteFacts(promotedFacts, episode);
        return episode;
    }

    /** 无事件流时，用工作记忆拼一个可读摘要。 */
    private String buildWorkingMemorySummary(WorkingMemory wm) {
        if (wm == null) {
            return "空任务";
        }
        List<String> parts = new ArrayList<>();
        if (wm.getCompletedTasks() != null && !wm.getCompletedTasks().isEmpty()) {
            parts.add("已完成 " + wm.getCompletedTasks().size() + " 项");
        }
        if (wm.getFailedTasks() != null && !wm.getFailedTasks().isEmpty()) {
            parts.add("失败 " + wm.getFailedTasks().size() + " 项");
        }
        return parts.isEmpty() ? "空任务" : String.join("，", parts);
    }

    /**
     * Episode 归属用户：工作记忆优先（它是当时执行的真实归属）。
     *
     * <p>工作记忆挂在 Redis TTL 上，超时即蒸发；此时不能就这么把 user_id 写成 null ——
     * 记忆检索按用户隔离，丢一次归属，这条 Episode 就再也检索不到了。所以退回本线程
     * 已绑定的归属（{@link MemoryStore#getCurrentUser()}），它来自 consolidate 入口按
     * sessionId 反查的用户，或交互线程已绑好的上下文。
     */
    private String resolveEpisodeUserId(WorkingMemory wm) {
        if (wm != null && wm.getUserId() != null && !wm.getUserId().isBlank()) {
            return wm.getUserId();
        }
        Long bound = MemoryStore.getCurrentUser();
        return bound == null ? null : String.valueOf(bound);
    }

    /** 把约束与产物压缩成一行观察记录。 */
    private String buildContextNote(WorkingMemory wm) {
        if (wm == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (wm.getConstraints() != null && !wm.getConstraints().isEmpty()) {
            parts.add("约束: " + summarizeMap(wm.getConstraints()));
        }
        if (wm.getArtifacts() != null && !wm.getArtifacts().isEmpty()) {
            parts.add("产物: " + summarizeMap(wm.getArtifacts()));
        }
        return parts.isEmpty() ? "" : String.join(" | ", parts);
    }

    /** 取前 5 个键值对，避免单条观察过长。 */
    private String summarizeMap(Map<String, Object> map) {
        return map.entrySet().stream()
            .limit(5)
            .map(e -> e.getKey() + "=" + truncate(String.valueOf(e.getValue()), 60))
            .collect(Collectors.joining(", "));
    }

    private Map<String, String> llmExtract(String eventSummary, String outcome, ChatModel model) {
        String prompt = """
            从以下 Agent 事件流中提炼一个情景记忆。

            事件流：
            %s

            结果：%s

            ## 不要写进记忆的内容
            - 可被当前代码或文件状态推导的内容（具体文件名、行号、目录结构）
            - git 历史可直接查证的内容（谁改的、何时提交、最近一次改动）
            - 一次性调试配方（"把 X 改成 Y 就不报错了"）—— 修复结果已经在代码里。
              但如果体现了可复用的规律（含"因为 / 根因 / 教训 / 下次"这类抽象），要保留。
            - 临时状态与一次性要求（"先这样""临时凑合"）

            ## 判断标准
            只保留"下次遇到类似任务时仍然用得上"的信息。流水账式的动作罗列没有价值。
            要提炼的是：目标是什么、卡在哪、怎么解决的、有没有规律。

            如果整个事件流确实没有任何可复用价值，只返回一行：
            skip: true

            否则用以下格式返回（每行一个字段，不要多余内容）：
            task: <一句话描述任务>
            actions: <动作1>;<动作2>;...
            observations: <观察1>;<观察2>;...
            resolution: <如果是失败，描述解决方案；成功则留空>
            facts: <可跨任务复用的事实1>;<事实2>；没有则留空
            """.formatted(eventSummary, outcome);

        ChatRequest request = ChatRequest.builder()
            .messages(List.of(
                SystemMessage.from("你是 Agent 记忆提炼器。只返回指定格式的内容。"),
                UserMessage.from(prompt)
            ))
            .build();

        String response = model.chat(request).aiMessage().text();
        return parseFields(response);
    }

    private Map<String, String> parseFields(String text) {
        Map<String, String> fields = new HashMap<>();
        for (String line : text.split("\n")) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                String key = line.substring(0, idx).trim().toLowerCase();
                String value = line.substring(idx + 1).trim();
                fields.put(key, value);
            }
        }
        return fields;
    }

    static List<String> splitFacts(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !"无".equals(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private void promoteFacts(List<String> facts, AgentEpisodeEntity episode) {
        if (memoryManager == null || facts == null || facts.isEmpty() || episode == null) {
            return;
        }
        String tenant = episode.getTenantId() != null && !episode.getTenantId().isBlank()
                ? episode.getTenantId() : MemoryStore.effectiveTenantId();
        String userId = episode.getUserId() != null && !episode.getUserId().isBlank()
                ? episode.getUserId() : MemoryStore.effectiveUserIdString();
        for (String factText : facts) {
            try {
                SemanticFact fact = new SemanticFact();
                fact.setTenantId(tenant);
                fact.setScope(MemoryScope.ofUser(tenant, userId));
                fact.setSubject(truncate(factText, FACT_SUBJECT_MAX));
                fact.setPredicate(MemoryKeys.PREDICATE_LEARNED);
                fact.setObjectValue(truncate(factText, FACT_OBJECT_MAX));
                fact.setSource(MemoryKeys.SOURCE_CONSOLIDATION);
                memoryManager.writeFact(fact);
            } catch (Exception e) {
                log.debug("巩固晋升事实失败: {}", e.getMessage());
            }
        }
    }

    private String buildFallbackSummary(List<AgentEventEntity> events) {
        long failCount = events.stream()
            .filter(e -> e.getStatus() == AgentEventEntity.EventStatus.FAILED).count();
        long toolCount = events.stream()
            .filter(e -> e.getEventType() == AgentEventEntity.EventType.TOOL_EXECUTION).count();
        return String.format("执行了 %d 个工具调用，%d 个失败", toolCount, failCount);
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String truncate(String s, int maxLen) {
        return StringUtils.truncate(s, maxLen);
    }
}
