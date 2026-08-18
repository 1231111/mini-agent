package com.miniagent.agent.memory.lifecycle;

import com.miniagent.common.StringUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.entity.AgentEventEntity;
import com.miniagent.agent.memory.entity.AgentEpisodeEntity;
import com.miniagent.agent.memory.repository.AgentEventRepository;
import com.miniagent.agent.memory.repository.AgentEpisodeRepository;
import com.miniagent.memory.lifecycle.ConsolidationService;
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
 * 默认巩固服务：session 结束时从事件流提炼 Episode。
 *
 * 流程：
 * 1. 查找该 session 所有未处理的事件
 * 2. 调 LLM 提炼为 Episode（任务摘要、动作序列、观察、结果、解决方案）
 * 3. 保存 Episode
 * 4. 标记事件为已处理
 */
@Service
public class DefaultConsolidationService implements ConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultConsolidationService.class);

    @Autowired
    private AgentEventRepository eventRepository;

    @Autowired
    private AgentEpisodeRepository episodeRepository;

    @Autowired(required = false)
    private ChatModel chatModel;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    @Transactional
    public void consolidate(String sessionId) {
        List<AgentEventEntity> events = eventRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        if (events.isEmpty()) return;

        // 只处理未处理的事件
        List<AgentEventEntity> unprocessed = events.stream()
            .filter(e -> !Boolean.TRUE.equals(e.getProcessed()))
            .collect(Collectors.toList());

        if (unprocessed.isEmpty()) return;

        try {
            // 提炼 Episode
            AgentEpisodeEntity episode = extractEpisode(unprocessed);
            if (episode != null) {
                episodeRepository.save(episode);
                log.info("巩固完成: session={}, episode={}", sessionId, episode.getTaskSummary());
            }

            // 标记为已处理
            List<Long> ids = unprocessed.stream().map(AgentEventEntity::getId).collect(Collectors.toList());
            eventRepository.markProcessed(ids);
        } catch (Exception e) {
            log.error("巩固失败: session={}", sessionId, e);
        }
    }

    private AgentEpisodeEntity extractEpisode(List<AgentEventEntity> events) {
        if (events.isEmpty()) return null;

        // 构建事件摘要
        StringBuilder eventSummary = new StringBuilder();
        for (AgentEventEntity event : events) {
            eventSummary.append("[").append(event.getEventType()).append("]");
            if (event.getStatus() != null) eventSummary.append(" ").append(event.getStatus());
            eventSummary.append(": ").append(truncate(event.getPayloadJson(), 200));
            eventSummary.append("\n");
        }

        // 判断是否有失败
        boolean hasFailure = events.stream()
            .anyMatch(e -> e.getStatus() == AgentEventEntity.EventStatus.FAILED
                || e.getEventType() == AgentEventEntity.EventType.ERROR
                || e.getEventType() == AgentEventEntity.EventType.TASK_FAIL);

        boolean hasSuccess = events.stream()
            .anyMatch(e -> e.getEventType() == AgentEventEntity.EventType.TASK_COMPLETE);

        AgentEpisodeEntity.Outcome outcome;
        if (hasSuccess && !hasFailure) outcome = AgentEpisodeEntity.Outcome.SUCCESS;
        else if (hasFailure && !hasSuccess) outcome = AgentEpisodeEntity.Outcome.FAILURE;
        else outcome = AgentEpisodeEntity.Outcome.PARTIAL;

        // 调 LLM 提炼（如果有）
        String taskSummary = null;
        String resolution = null;
        List<String> actions = new ArrayList<>();
        List<String> observations = new ArrayList<>();

        if (chatModel != null) {
            try {
                Map<String, String> extracted = llmExtract(eventSummary.toString(), outcome.name());
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
            } catch (Exception e) {
                log.warn("LLM 提炼 Episode 失败: {}", e.getMessage());
            }
        }

        // 回退：从事件构建
        if (taskSummary == null) {
            taskSummary = buildFallbackSummary(events);
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

        // 构建 Episode
        AgentEpisodeEntity episode = new AgentEpisodeEntity();
        episode.setTenantId(events.get(0).getTenantId());
        episode.setSessionId(events.get(0).getSessionId());
        episode.setTaskSummary(truncate(taskSummary, 500));
        episode.setOutcome(outcome);
        episode.setActionsJson(toJson(actions));
        episode.setObservationsJson(toJson(observations));
        episode.setResolution(resolution);
        episode.setImportance(hasFailure ? 0.8 : 0.5);

        return episode;
    }

    private Map<String, String> llmExtract(String eventSummary, String outcome) {
        String prompt = """
            从以下 Agent 事件流中提炼一个情景记忆。

            事件流：
            %s

            结果：%s

            请用以下格式返回（每行一个字段，不要多余内容）：
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
