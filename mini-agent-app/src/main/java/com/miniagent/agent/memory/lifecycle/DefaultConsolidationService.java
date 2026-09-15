package com.miniagent.agent.memory.lifecycle;

import com.miniagent.common.StringUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.entity.AgentEventEntity;
import com.miniagent.agent.memory.entity.AgentEpisodeEntity;
import com.miniagent.agent.memory.repository.AgentEventRepository;
import com.miniagent.agent.memory.repository.AgentEpisodeRepository;
import com.miniagent.memory.lifecycle.ConsolidationService;
import com.miniagent.memory.model.WorkingMemory;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private AgentEventRepository eventRepository;

    @Autowired
    private AgentEpisodeRepository episodeRepository;

    @Autowired
    private WorkingMemoryManager workingMemoryManager;

    @Autowired(required = false)
    private ChatModel chatModel;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    @Transactional
    public void consolidate(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }

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
        boolean llmSkipped = false;

        // 无事件流时不调 LLM：没有可提炼的原料
        if (chatModel != null && !events.isEmpty()) {
            try {
                Map<String, String> extracted = llmExtract(eventSummary.toString(), outcome.name());
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
                }
            } catch (Exception e) {
                log.warn("LLM 提炼 Episode 失败: {}", e.getMessage());
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
            episode.setUserId(wm.getUserId());
            episode.setProjectId(wm.getProjectId());
        }
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

    private Map<String, String> llmExtract(String eventSummary, String outcome) {
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
            """.formatted(eventSummary, outcome);

        ChatRequest request = ChatRequest.builder()
            .messages(List.of(
                SystemMessage.from("你是 Agent 记忆提炼器。只返回指定格式的内容。"),
                UserMessage.from(prompt)
            ))
            .build();

        String response = chatModel.chat(request).aiMessage().text();
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
