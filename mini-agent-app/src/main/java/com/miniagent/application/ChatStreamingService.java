package com.miniagent.application;

import com.miniagent.agent.core.SessionEventCenter;
import com.miniagent.config.service.TaskRunService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 流式连接管理：emitter 创建、重连、任务状态查询。
 * 从 AgentChatApplicationService 提取而来。
 */
@Service
public class ChatStreamingService {

    @Value("${agent.sse.timeout-ms:1800000}")
    private long sseTimeoutMs;

    @Autowired
    private SessionEventCenter eventCenter;
    @Autowired
    private TaskRunService taskRunService;

    /** 正在运行的任务：sessionId -> true。用于前端查询任务状态（内存快路径）。 */
    private final ConcurrentHashMap<String, Boolean> runningTasks = new ConcurrentHashMap<>();
    /** SSE 已建立但后台线程尚未完成启动的会话，用于避免同会话重复启动。 */
    private final Set<String> pendingRuns = ConcurrentHashMap.newKeySet();
    /** 终态事件正在发送时仍占用 session，防止新请求替换其 SSE channel。 */
    private final Set<String> terminalizingRuns = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();

    /** Local lifecycle only: admission must not recurse into TaskRunService or Redis. */
    public boolean hasLocalLifecycleReservation(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        synchronized (lifecycleLock) {
            return pendingRuns.contains(sessionId) || runningTasks.containsKey(sessionId)
                    || terminalizingRuns.contains(sessionId);
        }
    }

    public void markRunning(String sessionId) {
        synchronized (lifecycleLock) {
            pendingRuns.remove(sessionId);
            runningTasks.put(sessionId, Boolean.TRUE);
        }
    }

    public void markIdle(String sessionId) {
        synchronized (lifecycleLock) {
            runningTasks.remove(sessionId);
            pendingRuns.remove(sessionId);
            terminalizingRuns.remove(sessionId);
        }
    }

    /**
     * Mark an SSE run terminal while retaining its channel reservation until the
     * end/error event has been published.  This prevents a second request from
     * replacing the channel while the first request is still flushing its final
     * event, and makes completion callbacks non-cancelling.
     */
    public void markCompleting(String sessionId) {
        synchronized (lifecycleLock) {
            runningTasks.remove(sessionId);
            pendingRuns.remove(sessionId);
            terminalizingRuns.add(sessionId);
        }
    }

    public boolean isTaskRunning(String sessionId) {
        return runningTasks.containsKey(sessionId)
                || (taskRunService != null && taskRunService.isRunning(sessionId));
    }

    public long getSseTimeoutMs() {
        return sseTimeoutMs;
    }

    /**
     * 重连到正在运行（或刚结束仍在缓冲期）的会话流。
     * 返回已挂载的 emitter；若无活动通道，发 "gone" 让前端回退到数据库加载。
     *
     * @param userId 连接归属人。中枢按它登记 emitter，登出时才能精准摘掉该用户的流；
     *               只按 sessionId 记账的话，退登后长连接会继续收推送。
     */
    public SseEmitter attachStream(String sessionId, Long userId) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        boolean ok = eventCenter.attachClient(sessionId, userId, emitter);
        if (!ok) {
            try {
                emitter.send(SseEmitter.event().name("gone").data(""));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }
        return emitter;
    }

    /** 创建一个立即发送错误事件并关闭的 emitter。 */
    public SseEmitter createErrorEmitter(String eventName, String message) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().name(eventName).data(message));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /** A rejected stream must never schedule a worker or release the active run. */
    public record StreamStart(SseEmitter emitter, boolean accepted, String runToken) {}

    /** 创建标准 SSE emitter 并挂载到事件中枢。 */
    public SseEmitter createStream(String sessionId, String initialMessage, Long userId) {
        return tryCreateStream(sessionId, initialMessage, userId).emitter();
    }

    public StreamStart tryCreateStream(String sessionId, String initialMessage, Long userId) {
        return tryCreateStream(sessionId, initialMessage, userId, false);
    }

    public StreamStart tryCreateStreamForAcceptedRun(String sessionId, String initialMessage, Long userId) {
        return tryCreateStream(sessionId, initialMessage, userId, true);
    }

    private StreamStart tryCreateStream(String sessionId, String initialMessage, Long userId,
                                        boolean ownsRun) {
        synchronized (lifecycleLock) {
            // The worker is scheduled after this method returns. Reserve the
            // session before creating the channel so two rapid requests cannot
            // replace each other's SSE channel before TaskRunService acquires its
            // durable run lock.
            if (pendingRuns.contains(sessionId)
                    || runningTasks.containsKey(sessionId)
                    || terminalizingRuns.contains(sessionId)
                    || eventCenter.hasRunningSession(sessionId)
                    || (!ownsRun && taskRunService != null && taskRunService.isRunning(sessionId))) {
                return new StreamStart(createErrorEmitter("error", "该会话已有任务在运行"), false, null);
            }
            pendingRuns.add(sessionId);
        }
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        String token = null;
        try {
            token = eventCenter.startRun(sessionId, initialMessage);
            eventCenter.attachClient(sessionId, userId, emitter);
        } catch (RuntimeException e) {
            try {
                if (token != null) {
                    eventCenter.error(sessionId, "无法建立任务连接", token);
                }
            } finally {
                markIdle(sessionId);
            }
            throw e;
        }
        return new StreamStart(emitter, true, token);
    }

    public SessionEventCenter getEventCenter() {
        return eventCenter;
    }
}
