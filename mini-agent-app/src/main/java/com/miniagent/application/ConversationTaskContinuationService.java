package com.miniagent.application;

import com.miniagent.config.entity.ChatMessage;
import com.miniagent.config.entity.ChatTask;
import com.miniagent.config.repository.ChatMessageRepository;
import com.miniagent.config.repository.ChatTaskRepository;
import com.miniagent.common.ChatRole;
import com.miniagent.config.service.AgentToolHistoryCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.List;

/**
 * Binds a confirmation continuation to the original chat task card.
 * A continuation is a control action, so it must update the original answer
 * and progress projection instead of creating a synthetic user turn.
 */
@Service
public class ConversationTaskContinuationService {
    public record Binding(Long userId, String sessionId, Long chatTaskId, long taskId, String question,
                          String executionId, String expectedAnswer, String expectedProgress,
                          Long assistantMessageId, String expectedToolHistory) { }

    private static final ObjectMapper JSON = new ObjectMapper();
    // Keep the same complete-exchange bounds accepted by AgentToolHistoryCodec.decode.
    private static final int MAX_REPLAY_CALLS = 16;
    private static final int MAX_REPLAY_JSON_CHARS = 96_000;

    private final ChatTaskRepository tasks;
    private final ChatMessageRepository messages;

    public ConversationTaskContinuationService(ChatTaskRepository tasks, ChatMessageRepository messages) {
        this.tasks = tasks;
        this.messages = messages;
    }

    @Transactional(readOnly = true)
    public Binding prepare(Long userId, String sessionId, String executionId) {
        if (userId == null || sessionId == null || sessionId.isBlank()
                || executionId == null || executionId.isBlank()) return null;
        for (int page = 0; ; page++) {
            var rows = tasks.findByUserIdAndSessionIdAndDeletedFalseOrderByIdDesc(
                    userId, sessionId, PageRequest.of(page, 100));
            if (rows == null || rows.isEmpty()) return null;
            for (ChatTask task : rows) {
                TaskProgressSnapshot progress = TaskProgressSnapshot.fromJson(task.getTaskProgressJson());
                if (!Objects.equals(userId, task.getUserId()) || !sessionId.equals(task.getSessionId())
                        || task.isDeleted() || progress == null || !executionId.equals(progress.executionId())) continue;
                long taskId = progress.taskId(); // scope 0 is a valid first task identity
                ChatMessage assistant = messages
                        .findFirstByConversationIdAndRoleAndContentOrderByTimestampDescIdDesc(
                                sessionId, ChatRole.ASSISTANT.getValue(), task.getAnswer())
                        .orElse(null);
                if (assistant == null) return null; // never update only one of the paired projections
                return new Binding(userId, sessionId, task.getId(), taskId, task.getQuestion(), executionId,
                        task.getAnswer(), task.getTaskProgressJson(), assistant == null ? null : assistant.getId(),
                        assistant == null ? null : assistant.getToolHistoryJson());
            }
            if (!rows.hasNext()) return null;
        }
    }

    /**
     * Atomically updates the original card. A zero CAS result means a newer
     * turn already changed the card, so the caller must not overwrite it.
     */
    @Transactional
    public void complete(Long userId, String sessionId, Binding binding,
                         String answer, TaskProgressSnapshot progress) {
        completeInternal(userId, sessionId, binding, answer, progress, List.of());
    }

    private void completeInternal(Long userId, String sessionId, Binding binding,
                                  String answer, TaskProgressSnapshot progress,
                                  List<dev.langchain4j.data.message.ChatMessage> runMessages) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(progress, "progress");
        TaskProgressSnapshot previous = TaskProgressSnapshot.fromJson(binding.expectedProgress());
        if (!Objects.equals(userId, binding.userId()) || !Objects.equals(sessionId, binding.sessionId())
                || !binding.executionId().equals(progress.executionId())
                || progress.taskId() != binding.taskId()
                || previous == null || progress.version() < previous.version()) {
            throw new IllegalStateException("续跑结果与原任务身份或版本不匹配");
        }
        int changed = tasks.compareAndSetAnswerAndProgress(binding.chatTaskId(), userId, sessionId,
                binding.expectedAnswer(), binding.expectedProgress(), answer, progress.toJson());
        if (changed != 1) throw new IllegalStateException("原任务卡已被更新，拒绝覆盖较新的结果");
        if (binding.assistantMessageId() != null) {
            String mergedHistory = mergeHistory(binding.expectedToolHistory(), runMessages);
            int messageChanged = messages.compareAndSetContentAndHistory(binding.assistantMessageId(), sessionId,
                    ChatRole.ASSISTANT.getValue(), binding.expectedAnswer(), binding.expectedToolHistory(), answer,
                    mergedHistory);
            if (messageChanged != 1)
                throw new IllegalStateException("原助手消息已被更新，回滚本次续跑结果");
        }
    }

    @Transactional
    public void complete(Binding binding, String answer, TaskProgressSnapshot progress) {
        completeInternal(binding.userId(), binding.sessionId(), binding, answer, progress, List.of());
    }

    @Transactional
    public void complete(Binding binding, String answer, TaskProgressSnapshot progress,
                         List<dev.langchain4j.data.message.ChatMessage> runMessages) {
        completeInternal(binding.userId(), binding.sessionId(), binding, answer, progress,
                runMessages == null ? List.of() : runMessages);
    }

    private static String mergeHistory(String previous,
                                       List<dev.langchain4j.data.message.ChatMessage> runMessages) {
        if (runMessages.isEmpty()) return previous;
        String next = AgentToolHistoryCodec.encodeLatestTurn(runMessages);
        if (next == null || next.isBlank()) next = AgentToolHistoryCodec.encodeExchanges(runMessages);
        if (next == null || next.isBlank()) return previous;
        try {
            ArrayNode merged = JSON.createArrayNode();
            appendValidExchanges(merged, previous, false);
            // encodeLatestTurn/encodeExchanges can emit a v1-shaped array when
            // there is no final answer reasoning. These batches came directly
            // from the bound provider, so their opaque reasoning is still exact.
            appendValidExchanges(merged, next,
                    com.miniagent.common.model.EffectiveModelContext.currentSendThinking());
            // Final reasoning belongs to the replacement answer, never to the old answer.
            String thinking = AgentToolHistoryCodec.decodeAssistantThinking(next);
            while (!merged.isEmpty() && (callCount(merged) > MAX_REPLAY_CALLS
                    || serializeHistory(merged, thinking).length() > MAX_REPLAY_JSON_CHARS)) merged.remove(0);
            return serializeHistory(merged, thinking);
        } catch (java.io.IOException malformed) {
            throw new IllegalStateException("无法合并本次续跑工具历史", malformed);
        }
    }

    private static void appendValidExchanges(ArrayNode target, String history, boolean trustedLiveThinking)
            throws java.io.IOException {
        if (history == null || history.isBlank() || history.length() > MAX_REPLAY_JSON_CHARS) return;
        JsonNode root;
        try { root = JSON.readTree(history); }
        catch (java.io.IOException malformedLegacy) { return; }
        if (root == null) return;
        JsonNode exchanges = root.isArray() ? root : root.path("exchanges");
        if (!exchanges.isArray()) return;
        boolean exactThinking = trustedLiveThinking || (root.isObject() && root.path("version").asInt(0) >= 2);
        for (JsonNode exchange : exchanges) {
            // Validate each complete batch with the production decoder, preserving
            // its original JSON and opaque reasoning rather than re-encoding it.
            var isolated = !exactThinking ? JSON.createArrayNode().add(exchange)
                    : JSON.createObjectNode().put("version", 2)
                    .set("exchanges", JSON.createArrayNode().add(exchange));
            if (!AgentToolHistoryCodec.decode(JSON.writeValueAsString(isolated)).isEmpty()) {
                JsonNode copy = exchange.deepCopy();
                // v1 does not guarantee exact provider reasoning. Do not grant it
                // v2 integrity merely because a later continuation is v2.
                if (!exactThinking && copy.isObject()) ((com.fasterxml.jackson.databind.node.ObjectNode) copy).remove("thinking");
                target.add(copy);
            }
        }
    }

    private static int callCount(ArrayNode exchanges) {
        int count = 0;
        for (JsonNode exchange : exchanges) count += exchange.path("calls").size();
        return count;
    }

    private static String serializeHistory(ArrayNode exchanges, String thinking) throws java.io.IOException {
        var envelope = JSON.createObjectNode();
        envelope.put("version", 2); envelope.set("exchanges", exchanges); envelope.put("assistantThinking", thinking);
        return JSON.writeValueAsString(envelope);
    }
}
