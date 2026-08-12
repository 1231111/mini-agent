package com.miniagent.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.replica.ReplicaProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 会话事件中心：事件生产与 HTTP 连接解耦。
 * redis 模式：本机通道 + Redis 发布订阅跨实例；追加消息走 Redis 列表。
 */
@Slf4j
@Component
public class SessionEventCenter {

    private static final long DONE_TTL_MS = 120_000L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SSE_PREFIX = "sse:";
    private static final String PENDING_USER_MSG_PREFIX = "pending-user-msg:";

    private final Map<String, SessionChannel> channels = new ConcurrentHashMap<>();
    private final String instanceId = UUID.randomUUID().toString();
    private final ScheduledExecutorService cleaner =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "session-event-cleaner");
                t.setDaemon(true);
                return t;
            });

    @Autowired(required = false)
    private StringRedisTemplate redis;
    @Autowired(required = false)
    private RedisMessageListenerContainer redisListener;
    @Autowired(required = false)
    private ReplicaProperties replicaProperties;

    public SessionEventCenter() {
        cleaner.scheduleWithFixedDelay(this::removeExpiredChannels, 60, 60, TimeUnit.SECONDS);
    }

    @PostConstruct
    void subscribeRedis() {
        if (!redisMode() || redisListener == null) return;
        redisListener.addMessageListener(this::onRedisMessage, new PatternTopic(SSE_PREFIX + "*"));
        log.info("SessionEventCenter 已订阅 Redis 频道 pattern={}* instanceId={}", SSE_PREFIX, instanceId);
    }

    private boolean redisMode() {
        return replicaProperties != null && replicaProperties.isRedisMode() && redis != null;
    }

    enum Status { running, done, error }

    static final class SessionChannel {
        volatile Status status = Status.running;
        volatile String sessionId;
        volatile String userMessage = "";
        final StringBuilder think = new StringBuilder();
        final StringBuilder answer = new StringBuilder();
        volatile String progress = "";
        volatile String subgoalText = "";
        volatile int subgoalDone = 0;
        volatile int subgoalTotal = 0;
        /** 最新 todo 计划（供重连重放） */
        volatile List<Map<String, Object>> todoItems = List.of();
        volatile String finalAnswer = "";
        volatile String errorMsg = "";
        volatile long doneAt = 0L;
        final CopyOnWriteArrayList<SseEmitter> clients = new CopyOnWriteArrayList<>();
        final ConcurrentLinkedQueue<String> pendingUserMessages = new ConcurrentLinkedQueue<>();
    }

    private static String key(String sessionId) {
        return StringUtils.isBlank(sessionId) ? "default" : sessionId.trim();
    }

    public boolean hasRunningSession(String sessionId) {
        SessionChannel channel = channels.get(key(sessionId));
        return Objects.nonNull(channel) && channel.status == Status.running;
    }

    public void start(String sessionId, String userMessage) {
        String k = key(sessionId);
        SessionChannel channel = new SessionChannel();
        channel.sessionId = k;
        channel.userMessage = Optional.ofNullable(userMessage).orElse("");
        channels.put(k, channel);
        publishToOtherInstances(k, "start", "user", channel.userMessage);
    }

    public void publish(String sessionId, String name, Object data) {
        SessionChannel channel = channels.get(key(sessionId));
        if (Objects.isNull(channel)) return;
        String text = Objects.isNull(data) ? "" : String.valueOf(data);
        applyEvent(channel, name, text);
        sendToLocalClients(channel, name, data);
        publishToOtherInstances(key(sessionId), "event", name, data);
    }

    public void publishSubGoal(String sessionId, String subText, int done, int total) {
        SessionChannel channel = channels.get(key(sessionId));
        if (Objects.isNull(channel)) return;
        channel.subgoalText = Optional.ofNullable(subText).orElse("");
        channel.subgoalDone = done;
        channel.subgoalTotal = total;
        Object payload = subgoalPayload(subText, done, total);
        sendToLocalClients(channel, "subgoal", payload);
        publishToOtherInstances(key(sessionId), "event", "subgoal", payload);
    }

    /** 推送完整 todo/计划列表（前端勾选列表）。 */
    public void publishTodo(String sessionId, List<Map<String, Object>> items) {
        SessionChannel channel = channels.get(key(sessionId));
        if (Objects.isNull(channel)) return;
        List<Map<String, Object>> copy = items == null ? List.of() : List.copyOf(items);
        channel.todoItems = copy;
        Map<String, Object> payload = todoPayload(copy);
        sendToLocalClients(channel, "todo", payload);
        publishToOtherInstances(key(sessionId), "event", "todo", payload);
    }

    private static Map<String, Object> subgoalPayload(String text, int done, int total) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("text", Optional.ofNullable(text).orElse(""));
        map.put("done", done);
        map.put("total", total);
        return map;
    }

    private static Map<String, Object> todoPayload(List<Map<String, Object>> items) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("items", items == null ? List.of() : items);
        return map;
    }

    private void sendToLocalClients(SessionChannel channel, String name, Object data) {
        for (SseEmitter client : channel.clients) {
            if (!sendEvent(client, name, data)) {
                channel.clients.remove(client);
            }
        }
    }

    private static boolean sendEvent(SseEmitter client, String name, Object data) {
        try {
            client.send(SseEmitter.event().name(name).data(Optional.ofNullable(data).orElse("")));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean attachClient(String sessionId, SseEmitter emitter) {
        SessionChannel channel = channels.get(key(sessionId));
        if (Objects.isNull(channel)) {
            return false;
        }
        sendEvent(emitter, "session", Optional.ofNullable(channel.sessionId).orElse(""));
        if (Objects.nonNull(channel.userMessage) && !channel.userMessage.isEmpty()) {
            sendEvent(emitter, "user", channel.userMessage);
        }
        if (channel.think.length() > 0) {
            sendEvent(emitter, "thinking", channel.think.toString());
        }
        if (Objects.nonNull(channel.progress) && !channel.progress.isEmpty()) {
            sendEvent(emitter, "progress", channel.progress);
        }
        if (channel.todoItems != null && !channel.todoItems.isEmpty()) {
            sendEvent(emitter, "todo", todoPayload(channel.todoItems));
        } else if (channel.subgoalTotal > 0) {
            sendEvent(emitter, "subgoal", subgoalPayload(
                    channel.subgoalText, channel.subgoalDone, channel.subgoalTotal));
        }
        if (channel.answer.length() > 0) {
            sendEvent(emitter, "token", channel.answer.toString());
        }

        if (channel.status == Status.done) {
            sendEvent(emitter, "end", Optional.ofNullable(channel.finalAnswer).orElse(""));
            try { emitter.complete(); } catch (Exception ignored) {}
        } else if (channel.status == Status.error) {
            sendEvent(emitter, "error", Optional.ofNullable(channel.errorMsg).orElse("处理出错"));
            try { emitter.complete(); } catch (Exception ignored) {}
        } else {
            emitter.onCompletion(() -> channel.clients.remove(emitter));
            emitter.onTimeout(() -> channel.clients.remove(emitter));
            emitter.onError(e -> channel.clients.remove(emitter));
            channel.clients.add(emitter);
        }
        return true;
    }

    public void complete(String sessionId, String finalAnswer) {
        SessionChannel channel = channels.get(key(sessionId));
        if (Objects.isNull(channel)) return;
        channel.finalAnswer = Optional.ofNullable(finalAnswer).orElse("");
        channel.status = Status.done;
        channel.doneAt = System.currentTimeMillis();
        for (SseEmitter client : channel.clients) {
            sendEvent(client, "end", channel.finalAnswer);
            try { client.complete(); } catch (Exception ignored) {}
        }
        channel.clients.clear();
        publishToOtherInstances(key(sessionId), "complete", "end", channel.finalAnswer);
    }

    public void error(String sessionId, String message) {
        SessionChannel channel = channels.get(key(sessionId));
        if (Objects.isNull(channel)) return;
        channel.errorMsg = Optional.ofNullable(message).orElse("处理出错");
        channel.status = Status.error;
        channel.doneAt = System.currentTimeMillis();
        for (SseEmitter client : channel.clients) {
            sendEvent(client, "error", channel.errorMsg);
            try { client.complete(); } catch (Exception ignored) {}
        }
        channel.clients.clear();
        publishToOtherInstances(key(sessionId), "error", "error", channel.errorMsg);
    }

    /** 执行中追加用户消息（跨实例用 Redis 列表）。 */
    public boolean appendUserMessage(String sessionId, String message) {
        String k = key(sessionId);
        if (redisMode()) {
            try {
                redis.opsForList().leftPush(PENDING_USER_MSG_PREFIX + k, message);
                SessionChannel channel = channels.get(k);
                String ack = message.length() > 50 ? message.substring(0, 50) + "..." : message;
                if (channel != null && channel.status == Status.running) {
                    sendToLocalClients(channel, "append_ack", ack);
                }
                publishToOtherInstances(k, "event", "append_ack", ack);
                log.info("用户追加消息入队(redis): sessionId={}, length={}", sessionId, message.length());
                return true;
            } catch (Exception e) {
                log.warn("Redis 追加消息失败: {}", e.getMessage());
                return false;
            }
        }
        SessionChannel channel = channels.get(k);
        if (Objects.isNull(channel) || channel.status != Status.running) return false;
        channel.pendingUserMessages.offer(message);
        sendToLocalClients(channel, "append_ack", message.length() > 50 ? message.substring(0, 50) + "..." : message);
        log.info("用户追加消息入队: sessionId={}, length={}", sessionId, message.length());
        return true;
    }

    public List<String> takePendingUserMessages(String sessionId) {
        String k = key(sessionId);
        if (redisMode()) {
            List<String> result = new ArrayList<>();
            try {
                String msg;
                while ((msg = redis.opsForList().rightPop(PENDING_USER_MSG_PREFIX + k)) != null) {
                    result.add(msg);
                }
            } catch (Exception e) {
                log.warn("Redis 取出追加消息失败: {}", e.getMessage());
            }
            return result;
        }
        SessionChannel channel = channels.get(k);
        if (Objects.isNull(channel)) return List.of();
        List<String> result = new ArrayList<>();
        String msg;
        while (Objects.nonNull((msg = channel.pendingUserMessages.poll()))) {
            result.add(msg);
        }
        return result;
    }

    private void publishToOtherInstances(String sessionId, String type, String name, Object data) {
        if (!redisMode()) return;
        try {
            ObjectNode node = JSON.createObjectNode();
            node.put("instanceId", instanceId);
            node.put("type", type);
            node.put("name", name == null ? "" : name);
            node.put("sessionId", sessionId);
            if (data instanceof Map<?, ?> || data instanceof List<?>) {
                node.set("data", JSON.valueToTree(data));
            } else {
                node.put("data", data == null ? "" : String.valueOf(data));
            }
            redis.convertAndSend(SSE_PREFIX + sessionId, JSON.writeValueAsString(node));
        } catch (Exception e) {
            log.warn("SSE Redis 跨实例发布失败: {}", e.getMessage());
        }
    }

    private void onRedisMessage(Message message, byte[] pattern) {
        try {
            String body = new String(message.getBody(), StandardCharsets.UTF_8);
            JsonNode node = JSON.readTree(body);
            String from = node.path("instanceId").asText("");
            if (from.isBlank()) {
                from = node.path("podId").asText(""); // 兼容旧字段
            }
            if (instanceId.equals(from)) return;
            String sessionId = node.path("sessionId").asText("");
            if (sessionId.isBlank()) return;
            String type = node.path("type").asText("");
            String name = node.path("name").asText("");
            JsonNode dataNode = node.get("data");
            Object data = dataNode == null || dataNode.isNull() ? ""
                    : (dataNode.isTextual() ? dataNode.asText() : JSON.convertValue(dataNode, Object.class));

            SessionChannel channel = channels.computeIfAbsent(sessionId, sid -> {
                SessionChannel c = new SessionChannel();
                c.sessionId = sid;
                return c;
            });

            switch (type) {
                case "start" -> {
                    channel.status = Status.running;
                    channel.userMessage = String.valueOf(data);
                    channel.think.setLength(0);
                    channel.answer.setLength(0);
                    channel.doneAt = 0L;
                    sendToLocalClients(channel, "user", channel.userMessage);
                }
                case "complete" -> {
                    channel.finalAnswer = String.valueOf(data);
                    channel.status = Status.done;
                    channel.doneAt = System.currentTimeMillis();
                    for (SseEmitter client : channel.clients) {
                        sendEvent(client, "end", channel.finalAnswer);
                        try { client.complete(); } catch (Exception ignored) {}
                    }
                    channel.clients.clear();
                }
                case "error" -> {
                    channel.errorMsg = String.valueOf(data);
                    channel.status = Status.error;
                    channel.doneAt = System.currentTimeMillis();
                    for (SseEmitter client : channel.clients) {
                        sendEvent(client, "error", channel.errorMsg);
                        try { client.complete(); } catch (Exception ignored) {}
                    }
                    channel.clients.clear();
                }
                default -> {
                    if ("subgoal".equals(name) && data instanceof Map<?, ?> map) {
                        Object text = map.get("text");
                        channel.subgoalText = text == null ? "" : String.valueOf(text);
                        channel.subgoalDone = toInt(map.get("done"));
                        channel.subgoalTotal = toInt(map.get("total"));
                    } else if ("todo".equals(name) && data instanceof Map<?, ?> map) {
                        channel.todoItems = toTodoItems(map.get("items"));
                    } else {
                        applyEvent(channel, name, data == null ? "" : String.valueOf(data));
                    }
                    if (!"append_ack".equals(name) || !channel.clients.isEmpty()) {
                        sendToLocalClients(channel, name, data);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("SSE Redis 消息处理失败: {}", e.getMessage());
        }
    }

    private static void applyEvent(SessionChannel channel, String name, String text) {
        switch (name) {
            case "thinking" -> channel.think.append(text);
            case "token" -> channel.answer.append(text);
            // seal/reset：正文留给前端归档进时间线；服务端缓冲保留供重连，不再抹掉
            case "reset", "seal" -> { }
            case "progress" -> channel.progress = text;
            case "session" -> channel.sessionId = text;
            default -> { }
        }
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toTodoItems(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                Map<String, Object> row = new LinkedHashMap<>();
                m.forEach((k, v) -> row.put(String.valueOf(k), v));
                out.add(row);
            }
        }
        return List.copyOf(out);
    }

    private void removeExpiredChannels() {
        long now = System.currentTimeMillis();
        channels.entrySet().removeIf(e -> {
            SessionChannel channel = e.getValue();
            return channel.status != Status.running
                    && channel.doneAt > 0
                    && (now - channel.doneAt) > DONE_TTL_MS;
        });
    }
}
