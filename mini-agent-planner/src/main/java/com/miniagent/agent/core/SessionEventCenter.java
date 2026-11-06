package com.miniagent.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.replica.ReplicaProperties;
import jakarta.annotation.PreDestroy;
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
import java.time.Duration;
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
    private static final int THINK_BUFFER_MAX_CHARS = 16_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SSE_PREFIX = "sse:";
    private static final String PENDING_USER_MSG_PREFIX = "pending-user-msg:";
    private static final String PENDING_USER_MSG_STATE_PREFIX = "pending-user-msg-state:";
    private static final String PENDING_USER_MSG_RUN_PREFIX = "pending-user-msg-run:";
    private static final Duration PENDING_USER_MSG_TTL = Duration.ofHours(2);
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> REDIS_APPEND_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "local runToken = redis.call('GET', KEYS[1]) "
                            + "if not runToken then return 0 end "
                            + "local stateKey = ARGV[2] .. ARGV[3] .. ':' .. runToken "
                            + "if redis.call('GET', stateKey) ~= 'open' then return 0 end "
                            + "redis.call('LPUSH', KEYS[2], ARGV[1]) "
                            + "redis.call('EXPIRE', stateKey, ARGV[4]) "
                            + "redis.call('EXPIRE', KEYS[2], ARGV[4]) "
                            + "return 1", Long.class);
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> REDIS_CLOSE_RUN_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "local stateKey = ARGV[1] .. ARGV[2] .. ':' .. ARGV[3] "
                            + "redis.call('SET', stateKey, 'closed', 'EX', ARGV[4]) "
                            + "if redis.call('GET', KEYS[1]) == ARGV[3] then redis.call('DEL', KEYS[1]) end "
                            + "return 1", Long.class);
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<List> REDIS_DRAIN_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "local messages = redis.call('LRANGE', KEYS[1], 0, -1) "
                            + "redis.call('DEL', KEYS[1]) "
                            + "return messages", List.class);
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<List> REDIS_SEAL_SCRIPT =
            new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('GET', KEYS[1]) ~= ARGV[1] then return {0} end "
                            + "local stateKey = ARGV[2] .. ARGV[3] .. ':' .. ARGV[1] "
                            + "if redis.call('GET', stateKey) ~= 'open' then return {0} end "
                            + "local messages = redis.call('LRANGE', KEYS[2], 0, -1) "
                            + "if #messages > 0 then redis.call('DEL', KEYS[2]); return {1, cjson.encode(messages)} end "
                            + "redis.call('SET', stateKey, 'closed', 'EX', ARGV[4]) "
                            + "redis.call('DEL', KEYS[2]) "
                            + "return {2}", List.class);

    private final Map<String, SessionChannel> channels = new ConcurrentHashMap<>();
    /**
     * emitter -> 归属用户。挂载时登记，用于登出时精准摘流。
     * 只按 sessionId 记账的话，退出登录后已建立的长连接不会被摘掉，仍会继续收到该会话的推送。
     */
    private final Map<SseEmitter, Long> emitterOwner = new ConcurrentHashMap<>();
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

    /** Stop the cleaner on context shutdown/reload so old application contexts do not retain it. */
    @PreDestroy
    void shutdown() {
        cleaner.shutdownNow();
    }

    @PostConstruct
    void subscribeRedis() {
        if (!redisMode() || redisListener == null) {
            return;
        }
        redisListener.addMessageListener(this::onRedisMessage, new PatternTopic(SSE_PREFIX + "*"));
        log.info("SessionEventCenter 已订阅 Redis 频道 pattern={}* instanceId={}", SSE_PREFIX, instanceId);
    }

    private boolean redisMode() {
        return replicaProperties != null && replicaProperties.isRedisMode() && redis != null;
    }

    enum Status { running, done, error }

    static final class SessionChannel {
        /**
         * think / answer 两个缓冲的锁。
         *
         * <p>写入方是 agent loop 线程（{@link #applyEvent}）与 Redis 监听线程
         * （{@link #onRedisMessage} 的 start 分支），读取方是 HTTP 线程
         * （{@link #attachClient} 的重连重放）。裸 StringBuilder 在这几条线程间并发时，
         * append 与 toString / setLength 会交错，最轻的表现是字符丢漏
         * （"given a closing conditions" 会变成 "ivennaraclosingconaitons"）。
         *
         * <p>更重的一条是「读 length() → delete」，实测抛的是
         * {@code java.lang.ArrayIndexOutOfBoundsException: arraycopy: last destination index
         * 1800 out of bounds for byte[1614]}（出在 {@code AbstractStringBuilder.delete} 的
         * {@code System.arraycopy}）。成因是并发的 append 各自在
         * {@code ensureCapacityInternal} 里替换 value 数组，后写的一次用更小的数组覆盖了
         * 更大的，而 {@code delete} 仍按读到的大 count 去 copy；读端的 {@code toString()}
         * 同理可能抛 StringIndexOutOfBoundsException。该异常在 sendEvent 里被吞掉之后，
         * 会连带把刚重连成功的客户端当成死连接摘掉。
         *
         * <p>因此 append、setLength、以及「读长度 → 裁剪」这三类都必须持本锁，
         * 且读端取快照下发，不能边遍历边读。
         */
        final Object bufferLock = new Object();
        volatile Status status = Status.running;
        /** Serializes local append acknowledgements with the final empty-queue check. */
        final Object pendingUserMessageLock = new Object();
        boolean acceptingUserMessages = true;
        volatile String sessionId;
        volatile String runToken;
        volatile String userMessage = "";
        final StringBuilder think = new StringBuilder();
        final StringBuilder answer = new StringBuilder();
        volatile Object planApproval = null;
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
        channel.runToken = UUID.randomUUID().toString();
        channel.userMessage = Optional.ofNullable(userMessage).orElse("");
        channels.put(k, channel);
        if (redisMode()) {
            try {
                redis.delete(pendingQueueKey(k));
                redis.opsForValue().set(redisStateKey(k, channel.runToken), "open", PENDING_USER_MSG_TTL);
                redis.opsForValue().set(redisRunKey(k), channel.runToken, PENDING_USER_MSG_TTL);
            } catch (Exception e) {
                log.warn("Redis 追加消息状态初始化失败: sessionId={}, err={}", k, e.getMessage());
            }
        }
        publishToOtherInstances(k, "start", "user", channel.userMessage);
    }

    public void publish(String sessionId, String name, Object data) {
        SessionChannel channel = channels.get(key(sessionId));
        if (Objects.isNull(channel)) {
            return;
        }
        if ("plan_approval".equals(name)) {
            channel.planApproval = data;
            sendToLocalClients(channel, name, data);
            publishToOtherInstances(key(sessionId), "event", name, data);
            return;
        }
        String text = Objects.isNull(data) ? "" : String.valueOf(data);
        applyEvent(channel, name, text);
        sendToLocalClients(channel, name, data);
        publishToOtherInstances(key(sessionId), "event", name, data);
    }

    public void publishSubGoal(String sessionId, String subText, int done, int total) {
        SessionChannel channel = channels.get(key(sessionId));
        if (Objects.isNull(channel)) {
            return;
        }
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
        if (Objects.isNull(channel)) {
            return;
        }
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

    /** 在 bufferLock 保护下取缓冲快照；空缓冲返回空串（避免下发空事件）。 */
    private static String snapshot(SessionChannel channel, StringBuilder buffer) {
        synchronized (channel.bufferLock) {
            return buffer.length() > 0 ? buffer.toString() : "";
        }
    }

    public boolean attachClient(String sessionId, Long userId, SseEmitter emitter) {
        SessionChannel channel = channels.get(key(sessionId));
        if (Objects.isNull(channel)) {
            return false;
        }
        sendEvent(emitter, "session", Optional.ofNullable(channel.sessionId).orElse(""));
        if (Objects.nonNull(channel.userMessage) && !channel.userMessage.isEmpty()) {
            sendEvent(emitter, "user", channel.userMessage);
        }
        String thinkSnapshot = snapshot(channel, channel.think);
        if (!thinkSnapshot.isEmpty()) {
            sendEvent(emitter, "thinking", thinkSnapshot);
        }
        if (channel.planApproval != null) {
            sendEvent(emitter, "plan_approval", channel.planApproval);
        }
        if (channel.todoItems != null && !channel.todoItems.isEmpty()) {
            sendEvent(emitter, "todo", todoPayload(channel.todoItems));
        } else if (channel.subgoalTotal > 0) {
            sendEvent(emitter, "subgoal", subgoalPayload(
                    channel.subgoalText, channel.subgoalDone, channel.subgoalTotal));
        }
        String answerSnapshot = snapshot(channel, channel.answer);
        if (!answerSnapshot.isEmpty()) {
            sendEvent(emitter, "token", answerSnapshot);
        }

        if (channel.status == Status.done) {
            sendEvent(emitter, "end", Optional.ofNullable(channel.finalAnswer).orElse(""));
            try { emitter.complete(); } catch (Exception ignored) {}
        } else if (channel.status == Status.error) {
            sendEvent(emitter, "error", Optional.ofNullable(channel.errorMsg).orElse("处理出错"));
            try { emitter.complete(); } catch (Exception ignored) {}
        } else {
            if (Objects.nonNull(userId)) {
                emitterOwner.put(emitter, userId);
            }
            emitter.onCompletion(() -> detachClient(channel, emitter));
            emitter.onTimeout(() -> detachClient(channel, emitter));
            emitter.onError(e -> detachClient(channel, emitter));
            channel.clients.add(emitter);
        }
        return true;
    }

    /** 摘掉一个 emitter：从通道列表移除，同时清掉归属登记。 */
    private void detachClient(SessionChannel channel, SseEmitter emitter) {
        channel.clients.remove(emitter);
        emitterOwner.remove(emitter);
    }

    /**
     * 摘掉某个用户挂在所有会话上的事件流 —— 登出时调用。
     *
     * <p>SSE 是长连接，只在建立那一次鉴权；不在登出时主动摘，这条连接会继续把该会话的
     * 推送送给一个已经退登的页面。
     *
     * @return 实际摘除的连接数
     */
    public int detachUser(Long userId) {
        if (Objects.isNull(userId)) {
            return 0;
        }
        int removed = 0;
        for (SessionChannel channel : channels.values()) {
            for (SseEmitter emitter : channel.clients) {
                if (!userId.equals(emitterOwner.get(emitter))) {
                    continue;
                }
                try {
                    emitter.send(SseEmitter.event().name("logout").data(""));
                } catch (Exception ignored) {
                    // 对端已断开，直接摘
                }
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 同上
                }
                detachClient(channel, emitter);
                removed++;
            }
        }
        if (removed > 0) {
            log.info("登出摘流 userId={} 关闭连接数={}", userId, removed);
        }
        return removed;
    }

    public void complete(String sessionId, String finalAnswer) {
        SessionChannel channel = channels.get(key(sessionId));
        if (Objects.isNull(channel)) {
            return;
        }
        synchronized (channel.pendingUserMessageLock) {
            channel.status = Status.done;
            channel.finalAnswer = Optional.ofNullable(finalAnswer).orElse("");
            closeRedisAcceptance(sessionId);
        }
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
        if (Objects.isNull(channel)) {
            return;
        }
        synchronized (channel.pendingUserMessageLock) {
            channel.errorMsg = Optional.ofNullable(message).orElse("处理出错");
            channel.status = Status.error;
            closeRedisAcceptance(sessionId);
        }
        channel.doneAt = System.currentTimeMillis();
        for (SseEmitter client : channel.clients) {
            sendEvent(client, "error", channel.errorMsg);
            try { client.complete(); } catch (Exception ignored) {}
        }
        channel.clients.clear();
        publishToOtherInstances(key(sessionId), "error", "error", channel.errorMsg);
    }

    /** 执行中追加用户消息；只有完成原子入队后才向调用方确认成功。 */
    public boolean appendUserMessage(String sessionId, String message) {
        String k = key(sessionId);
        SessionChannel channel = channels.get(k);
        if (channel == null || channel.status != Status.running) {
            return false;
        }
        if (redisMode()) {
            try {
                Long accepted = redis.execute(REDIS_APPEND_SCRIPT,
                        List.of(redisRunKey(k), pendingQueueKey(k)),
                        Objects.requireNonNullElse(message, ""),
                        PENDING_USER_MSG_STATE_PREFIX,
                        k,
                        String.valueOf(PENDING_USER_MSG_TTL.toSeconds()));
                if (!Long.valueOf(1L).equals(accepted)) {
                    return false;
                }
            } catch (Exception e) {
                log.warn("Redis 追加消息失败: {}", e.getMessage());
                return false;
            }
        } else {
            synchronized (channel.pendingUserMessageLock) {
                if (channel.status != Status.running || !channel.acceptingUserMessages) {
                    return false;
                }
                channel.pendingUserMessages.offer(Objects.requireNonNullElse(message, ""));
            }
        }
        String value = Objects.requireNonNullElse(message, "");
        String ack = value.length() > 50 ? value.substring(0, 50) + "..." : value;
        sendToLocalClients(channel, "append_ack", ack);
        publishToOtherInstances(k, "event", "append_ack", ack);
        log.info("用户追加消息入队: sessionId={}, length={}", sessionId, value.length());
        return true;
    }

    public List<String> takePendingUserMessages(String sessionId) {
        String k = key(sessionId);
        if (redisMode()) {
            try {
                List<String> values = redis.execute(REDIS_DRAIN_SCRIPT,
                        List.of(pendingQueueKey(k)));
                if (values == null || values.isEmpty()) {
                    return List.of();
                }
                java.util.Collections.reverse(values);
                return values;
            } catch (Exception e) {
                log.warn("Redis 取出追加消息失败: {}", e.getMessage());
                return List.of();
            }
        }
        SessionChannel channel = channels.get(k);
        if (channel == null) {
            return List.of();
        }
        synchronized (channel.pendingUserMessageLock) {
            List<String> result = new ArrayList<>();
            String msg;
            while ((msg = channel.pendingUserMessages.poll()) != null) {
                result.add(msg);
            }
            return result;
        }
    }

    /**
     * 原子地处理正常完成边界。返回 queued=true 时把追加消息交给循环继续执行；
     * 返回 queued=false 时会话已关闭，后续追加必定被拒绝。
     */
    public SealResult sealIfNoPendingUserMessages(String sessionId) {
        String k = key(sessionId);
        SessionChannel channel = channels.get(k);
        if (channel == null) {
            return new SealResult(true, List.of());
        }
        if (redisMode()) {
            try {
                List<?> result = redis.execute(REDIS_SEAL_SCRIPT,
                        List.of(redisRunKey(k), pendingQueueKey(k)),
                        channel.runToken,
                        PENDING_USER_MSG_STATE_PREFIX,
                        k,
                        String.valueOf(PENDING_USER_MSG_TTL.toSeconds()));
                if (result == null || result.isEmpty()) {
                    return new SealResult(true, List.of());
                }
                long status = Long.parseLong(String.valueOf(result.get(0)));
                if (status == 1L) {
                    String encoded = String.valueOf(result.get(1));
                    List<String> messages = JSON.readValue(encoded,
                            JSON.getTypeFactory().constructCollectionType(List.class, String.class));
                    java.util.Collections.reverse(messages);
                    return new SealResult(true, messages);
                }
                if (status == 2L) {
                    return new SealResult(false, List.of());
                }
                return new SealResult(false, List.of());
            } catch (Exception e) {
                log.warn("Redis 任务封存检查失败: sessionId={}, err={}", k, e.getMessage());
                return new SealResult(false, List.of(), true);
            }
        }
        synchronized (channel.pendingUserMessageLock) {
            List<String> pending = new ArrayList<>();
            String msg;
            while ((msg = channel.pendingUserMessages.poll()) != null) {
                pending.add(msg);
            }
            if (!pending.isEmpty()) {
                return new SealResult(true, pending);
            }
            channel.acceptingUserMessages = false;
            closeRedisAcceptance(k);
            return new SealResult(false, List.of());
        }
    }

    public record SealResult(boolean queued, List<String> messages, boolean failed) {
        public SealResult(boolean queued, List<String> messages) {
            this(queued, messages, false);
        }
    }

    private static String pendingQueueKey(String sessionId) {
        return PENDING_USER_MSG_PREFIX + sessionId;
    }

    private static String redisStateKey(String sessionId, String runToken) {
        return PENDING_USER_MSG_STATE_PREFIX + sessionId + ":" + runToken;
    }

    private static String redisRunKey(String sessionId) {
        return PENDING_USER_MSG_RUN_PREFIX + sessionId;
    }

    private void closeRedisAcceptance(String sessionId) {
        if (!redisMode()) {
            return;
        }
        SessionChannel channel = channels.get(key(sessionId));
        if (channel == null || channel.runToken == null) {
            return;
        }
        try {
            redis.execute(REDIS_CLOSE_RUN_SCRIPT,
                    List.of(redisRunKey(sessionId)),
                    PENDING_USER_MSG_STATE_PREFIX,
                    sessionId,
                    channel.runToken,
                    String.valueOf(PENDING_USER_MSG_TTL.toSeconds()));
        } catch (Exception e) {
            log.debug("关闭 Redis 追加消息接收失败 session={}: {}", sessionId, e.getMessage());
        }
    }

    private void publishToOtherInstances(String sessionId, String type, String name, Object data) {
        if (!redisMode()) {
            return;
        }
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
            if (instanceId.equals(from)) {
                return;
            }
            String sessionId = node.path("sessionId").asText("");
            if (sessionId.isBlank()) {
                return;
            }
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
                    channel.runToken = null;
                    channel.userMessage = String.valueOf(data);
                    synchronized (channel.bufferLock) {
                        channel.think.setLength(0);
                        channel.answer.setLength(0);
                    }
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
                    if ("plan_approval".equals(name)) {
                        channel.planApproval = data;
                    } else if ("subgoal".equals(name) && data instanceof Map<?, ?> map) {
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
        if ("plan_approval".equals(name)) {
            try {
                channel.planApproval = JSON.readValue(text, Object.class);
            } catch (Exception ignored) {
                channel.planApproval = Map.of("required", true);
            }
            return;
        }
        switch (name) {
            case "thinking" -> {
                // append 与「读 length() → delete」必须在同一把锁内：否则并发 append 会让
                // 裁剪右端点越过数组末尾，抛 StringIndexOutOfBoundsException。
                synchronized (channel.bufferLock) {
                    channel.think.append(text);
                    int max = THINK_BUFFER_MAX_CHARS;
                    if (channel.think.length() > max)
                        channel.think.delete(0, channel.think.length() - max);
                }
            }
            case "token" -> {
                synchronized (channel.bufferLock) {
                    channel.answer.append(text);
                }
            }
            // seal/reset：正文留给前端归档进时间线；服务端缓冲保留供重连，不再抹掉
            case "reset", "seal" -> { }
            case "session" -> channel.sessionId = text;
            default -> { }
        }
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try { return Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return 0; }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> toTodoItems(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
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
