package com.miniagent.agent.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 会话事件中枢：把「事件生产」与「HTTP 连接」解耦。
 *
 * 每个 sessionId 对应一个 {@link Channel}，agent 把事件 publish 到 Channel，
 * Channel 同时做两件事：
 *   1) 按「累积状态」缓冲（think/answer 全文、最新 progress/subgoal）；
 *   2) 扇出给当前挂着的 0~N 个 SseEmitter（实时客户端）。
 *
 * 客户端刷新 / 新开浏览器后调用 {@link #attach}：先重放累积状态，再接收实时事件，
 * 体验与未刷新一致（内容完整，但重连后是瞬间呈现而非逐字打字动画）。
 *
 * 任务结束后 Channel 保留 {@link #DONE_TTL_MS}，应对「刚好结束时刷新」，到期驱逐防内存泄漏。
 */
@Slf4j
@Component
public class SessionStreamHub {

    /** 任务结束后 Channel 的保留时长（毫秒）。 */
    private static final long DONE_TTL_MS = 120_000L;

    private final Map<String, Channel> channels = new ConcurrentHashMap<>();
    private final ScheduledExecutorService janitor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stream-hub-janitor");
                t.setDaemon(true);
                return t;
            });

    public SessionStreamHub() {
        // 周期性清理已过期的 done/error channel
        janitor.scheduleWithFixedDelay(this::sweep, 60, 60, TimeUnit.SECONDS);
    }

    enum Status { running, done, error }

    /** 单会话的事件通道：累积状态 + 实时订阅者。 */
    static final class Channel {
        volatile Status status = Status.running;
        volatile String sessionId;
        volatile String userMessage = "";
        final StringBuilder think = new StringBuilder();
        final StringBuilder answer = new StringBuilder();
        volatile String progress = "";
        volatile String subgoalText = "";
        volatile int subgoalDone = 0;
        volatile int subgoalTotal = 0;
        volatile String finalAnswer = "";
        volatile String errorMsg = "";
        volatile long doneAt = 0L;  // 结束时间戳，用于 TTL 驱逐
        final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        final ConcurrentLinkedQueue<String> pendingUserMessages = new ConcurrentLinkedQueue<>();
    }

    private static String key(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId.trim();
    }

    /** 是否存在该会话的活动（running）通道。 */
    public boolean hasActiveChannel(String sessionId) {
        Channel ch = channels.get(key(sessionId));
        return ch != null && ch.status == Status.running;
    }

    /** 开始一个新任务：重置该会话的 Channel（丢弃上一轮缓冲）。 */
    public void start(String sessionId, String userMessage) {
        String k = key(sessionId);
        Channel ch = new Channel();
        ch.sessionId = k;
        ch.userMessage = userMessage == null ? "" : userMessage;
        channels.put(k, ch);
    }

    /**
     * 发布一个事件：更新累积状态，并把原始 delta 扇出给所有实时订阅者。
     * send 失败（客户端已断开）的 emitter 自动移除 —— 断线自愈。
     */
    public void publish(String sessionId, String name, Object data) {
        Channel ch = channels.get(key(sessionId));
        if (ch == null) return;
        String text = data == null ? "" : String.valueOf(data);
        // 更新累积状态
        switch (name) {
            case "thinking" -> ch.think.append(text);
            case "token"    -> ch.answer.append(text);
            case "reset"    -> ch.answer.setLength(0);
            case "progress" -> ch.progress = text;
            case "session"  -> ch.sessionId = text;
            default -> { /* subgoal 走专用方法；其余事件只扇出不缓存 */ }
        }
        fanOut(ch, name, data);
    }

    /** 子目标事件（结构化）：缓存最新值并扇出。 */
    public void publishSubGoal(String sessionId, String subText, int done, int total) {
        Channel ch = channels.get(key(sessionId));
        if (ch == null) return;
        ch.subgoalText = subText == null ? "" : subText;
        ch.subgoalDone = done;
        ch.subgoalTotal = total;
        fanOut(ch, "subgoal", subgoalPayload(subText, done, total));
    }

    private static Map<String, Object> subgoalPayload(String text, int done, int total) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", text == null ? "" : text);
        m.put("done", done);
        m.put("total", total);
        return m;
    }

    private void fanOut(Channel ch, String name, Object data) {
        for (SseEmitter em : ch.emitters) {
            if (!sendEvent(em, name, data)) {
                ch.emitters.remove(em);  // 客户端已断开
            }
        }
    }

    private static boolean sendEvent(SseEmitter em, String name, Object data) {
        try {
            em.send(SseEmitter.event().name(name).data(data == null ? "" : data));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 客户端（首次 POST / 刷新 / 新浏览器）挂载到该会话通道。
     * 先重放累积状态（session→user→thinking 全文→progress→subgoal→answer 全文），
     * 再决定是补发结束事件并关闭，还是加入实时订阅集合继续接收。
     *
     * @return true 表示成功挂上（含已重放完结束的情形）；false 表示无此通道，调用方应回退到数据库加载。
     */
    public boolean attach(String sessionId, SseEmitter emitter) {
        Channel ch = channels.get(key(sessionId));
        if (ch == null) {
            return false;  // 无活动通道：任务早已结束且缓冲被驱逐
        }
        // —— 重放累积状态 ——
        sendEvent(emitter, "session", ch.sessionId == null ? "" : ch.sessionId);
        if (ch.userMessage != null && !ch.userMessage.isEmpty()) {
            sendEvent(emitter, "user", ch.userMessage);
        }
        if (ch.think.length() > 0) {
            sendEvent(emitter, "thinking", ch.think.toString());
        }
        if (ch.progress != null && !ch.progress.isEmpty()) {
            sendEvent(emitter, "progress", ch.progress);
        }
        if (ch.subgoalTotal > 0) {
            sendEvent(emitter, "subgoal", subgoalPayload(ch.subgoalText, ch.subgoalDone, ch.subgoalTotal));
        }
        if (ch.answer.length() > 0) {
            sendEvent(emitter, "token", ch.answer.toString());
        }

        // —— 已结束：补发收尾并关闭；未结束：加入实时集合 ——
        if (ch.status == Status.done) {
            sendEvent(emitter, "end", ch.finalAnswer == null ? "" : ch.finalAnswer);
            try { emitter.complete(); } catch (Exception ignored) {}
        } else if (ch.status == Status.error) {
            sendEvent(emitter, "error", ch.errorMsg == null ? "处理出错" : ch.errorMsg);
            try { emitter.complete(); } catch (Exception ignored) {}
        } else {
            emitter.onCompletion(() -> ch.emitters.remove(emitter));
            emitter.onTimeout(() -> ch.emitters.remove(emitter));
            emitter.onError(e -> ch.emitters.remove(emitter));
            ch.emitters.add(emitter);
        }
        return true;
    }

    /** 任务正常结束：缓存权威全文，发 end，断开所有订阅者，标记 TTL。 */
    public void complete(String sessionId, String finalAnswer) {
        Channel ch = channels.get(key(sessionId));
        if (ch == null) return;
        ch.finalAnswer = finalAnswer == null ? "" : finalAnswer;
        ch.status = Status.done;
        ch.doneAt = System.currentTimeMillis();
        for (SseEmitter em : ch.emitters) {
            sendEvent(em, "end", ch.finalAnswer);
            try { em.complete(); } catch (Exception ignored) {}
        }
        ch.emitters.clear();
    }

    /** 任务异常结束。 */
    public void error(String sessionId, String message) {
        Channel ch = channels.get(key(sessionId));
        if (ch == null) return;
        ch.errorMsg = message == null ? "处理出错" : message;
        ch.status = Status.error;
        ch.doneAt = System.currentTimeMillis();
        for (SseEmitter em : ch.emitters) {
            sendEvent(em, "error", ch.errorMsg);
            try { em.complete(); } catch (Exception ignored) {}
        }
        ch.emitters.clear();
    }

    /** 用户在执行中追加消息：入队并通知前端已收到。 */
    public boolean injectMessage(String sessionId, String message) {
        Channel ch = channels.get(key(sessionId));
        if (ch == null || ch.status != Status.running) return false;
        ch.pendingUserMessages.offer(message);
        fanOut(ch, "inject_ack", message.length() > 50 ? message.substring(0, 50) + "..." : message);
        log.info("用户追加消息入队: sessionId={}, length={}", sessionId, message.length());
        return true;
    }

    /** AgentLoop 每轮迭代开头调用：取出所有待处理消息（线程安全）。 */
    public List<String> drainMessages(String sessionId) {
        Channel ch = channels.get(key(sessionId));
        if (ch == null) return List.of();
        List<String> result = new ArrayList<>();
        String msg;
        while ((msg = ch.pendingUserMessages.poll()) != null) {
            result.add(msg);
        }
        return result;
    }

    /** 周期清理：已结束且超过 TTL 的通道驱逐。 */
    private void sweep() {
        long now = System.currentTimeMillis();
        channels.entrySet().removeIf(e -> {
            Channel ch = e.getValue();
            return ch.status != Status.running
                    && ch.doneAt > 0
                    && (now - ch.doneAt) > DONE_TTL_MS;
        });
    }
}
