package com.miniagent.agent.trace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.common.MessageConstants;
import com.miniagent.config.entity.AgentTraceStep;
import com.miniagent.replica.ReplicaProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 轨迹页 SSE：落库后推送，替代前端轮询。
 * ponytail: 本机通道 + Redis 扇出（与 SessionEventCenter 同模式）。
 */
@Slf4j
@Component
public class TraceSseHub {

    private static final String REDIS_PREFIX = "trace-sse:";
    private static final long HEARTBEAT_INTERVAL_MS = 15_000L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INSTANCE_ID = UUID.randomUUID().toString();

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> clients =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "trace-sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    @Value("${agent.sse.timeout-ms:1800000}")
    private long sseTimeoutMs;

    @Autowired(required = false)
    private StringRedisTemplate redis;
    @Autowired(required = false)
    private RedisMessageListenerContainer redisListener;
    @Autowired(required = false)
    private ReplicaProperties replicaProperties;

    public TraceSseHub() {
        heartbeat.scheduleWithFixedDelay(
                this::sendHeartbeat, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    @PostConstruct
    void subscribeRedis() {
        if (!redisMode() || redisListener == null) return;
        redisListener.addMessageListener(this::onRedisMessage, new PatternTopic(REDIS_PREFIX + "*"));
        log.info("TraceSseHub 已订阅 Redis 频道 pattern={}*", REDIS_PREFIX);
    }

    public SseEmitter attach(String sessionId) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        String key = key(sessionId);
        CopyOnWriteArrayList<SseEmitter> list =
                clients.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable drop = () -> remove(key, emitter);
        emitter.onCompletion(drop);
        emitter.onTimeout(drop);
        emitter.onError(e -> drop.run());
        return emitter;
    }

    public void publish(AgentTraceStep step) {
        if (step == null || StringUtils.isBlank(step.getSessionId())) return;
        Map<String, Object> payload = payload(step);
        sendLocal(step.getSessionId(), payload);
        publishRedis(step.getSessionId(), payload);
    }

    static Map<String, Object> payload(AgentTraceStep step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", step.getId());
        map.put("sessionId", step.getSessionId());
        map.put("executionId", Optional.ofNullable(step.getExecutionId()).orElse(""));
        map.put("stepType", Optional.ofNullable(step.getStepType()).orElse(""));
        map.put("status", Optional.ofNullable(step.getStatus()).orElse(""));
        return map;
    }

    private void sendLocal(String sessionId, Map<String, Object> payload) {
        CopyOnWriteArrayList<SseEmitter> list = clients.get(key(sessionId));
        if (list == null || list.isEmpty()) return;
        for (SseEmitter em : list) {
            if (!sendEvent(em, payload)) list.remove(em);
        }
        if (list.isEmpty()) clients.remove(key(sessionId), list);
    }

    private static boolean sendEvent(SseEmitter em, Map<String, Object> payload) {
        try {
            em.send(SseEmitter.event()
                    .name(MessageConstants.TRACE_SSE_EVENT)
                    .data(JSON.writeValueAsString(payload)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void sendHeartbeat() {
        for (var entry : clients.entrySet()) {
            CopyOnWriteArrayList<SseEmitter> list = entry.getValue();
            for (SseEmitter em : list) {
                try {
                    em.send(SseEmitter.event().comment("hb"));
                } catch (Exception e) {
                    list.remove(em);
                }
            }
            if (list.isEmpty()) clients.remove(entry.getKey(), list);
        }
    }

    private void remove(String key, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = clients.get(key);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) clients.remove(key, list);
    }

    private boolean redisMode() {
        return replicaProperties != null && replicaProperties.isRedisMode() && redis != null;
    }

    private void publishRedis(String sessionId, Map<String, Object> payload) {
        if (!redisMode()) return;
        try {
            ObjectNode node = JSON.createObjectNode();
            node.put("instanceId", INSTANCE_ID);
            node.put("sessionId", sessionId);
            node.set("data", JSON.valueToTree(payload));
            redis.convertAndSend(REDIS_PREFIX + sessionId, JSON.writeValueAsString(node));
        } catch (Exception e) {
            log.warn("轨迹 SSE Redis 发布失败: {}", e.getMessage());
        }
    }

    private void onRedisMessage(Message message, byte[] pattern) {
        try {
            JsonNode node = JSON.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            String from = node.path("instanceId").asText("");
            if (INSTANCE_ID.equals(from)) return;
            String sessionId = node.path("sessionId").asText("");
            if (sessionId.isBlank()) return;
            JsonNode dataNode = node.get("data");
            if (dataNode == null || dataNode.isNull()) return;
            Map<String, Object> payload = JSON.convertValue(
                    dataNode, new TypeReference<Map<String, Object>>() {});
            sendLocal(sessionId, payload);
        } catch (Exception e) {
            log.warn("轨迹 SSE Redis 消息处理失败: {}", e.getMessage());
        }
    }

    private static String key(String sessionId) {
        return StringUtils.isBlank(sessionId) ? "default" : sessionId.trim();
    }
}
