package com.miniagent.application;

import com.miniagent.agent.core.SessionEventCenter;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.common.MessageConstants;
import com.miniagent.config.service.TaskRunService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 流式连接管理：emitter 创建、重连、任务状态查询。
 * 从 AgentChatApplicationService 提取而来。
 */
@Service
@Slf4j
public class ChatStreamingService {

    @Value("${agent.sse.timeout-ms:1800000}")
    private long sseTimeoutMs;

    @Autowired
    private SessionEventCenter eventCenter;
    @Autowired
    private TaskRunService taskRunService;
    @Autowired(required = false)
    private ExecutionControl executionControl;

    /** 正在运行的任务：sessionId -> true。用于前端查询任务状态（内存快路径）。 */
    private final ConcurrentHashMap<String, Boolean> runningTasks = new ConcurrentHashMap<>();
    /**
     * SSE 已建立但后台线程尚未完成 executionControl.start 的会话。
     * 断连可能先于异步 worker 调度；保留这个状态才能把取消信号交给随后创建的租约。
     */
    private final Set<String> pendingRuns = ConcurrentHashMap.newKeySet();
    /** 断连发生在 executionControl.start 与 markRunning 之间时暂存取消请求。 */
    private final Set<String> pendingDisconnects = ConcurrentHashMap.newKeySet();
    /** 终态事件正在发送时仍占用 session，防止新请求替换其 SSE channel。 */
    private final Set<String> terminalizingRuns = ConcurrentHashMap.newKeySet();
    /** Generation token prevents a late callback from an old emitter cancelling a new run. */
    private final ConcurrentHashMap<String, Long> lifecycleTokens = new ConcurrentHashMap<>();
    private final AtomicLong lifecycleSequence = new AtomicLong();
    private final Object lifecycleLock = new Object();

    public void markRunning(String sessionId) {
        boolean cancelImmediately;
        synchronized (lifecycleLock) {
            pendingRuns.remove(sessionId);
            runningTasks.put(sessionId, Boolean.TRUE);
            cancelImmediately = pendingDisconnects.remove(sessionId);
        }
        if (cancelImmediately && executionControl != null) {
            try {
                executionControl.cancel(sessionId);
                log.info("SSE 在任务启动前断连，已向执行控制面发送取消信号 session={}", sessionId);
            } catch (Exception e) {
                log.warn("SSE 启动竞态取消任务失败 session={}: {}", sessionId, e.getMessage());
            }
        }
    }

    public void markIdle(String sessionId) {
        synchronized (lifecycleLock) {
            runningTasks.remove(sessionId);
            pendingRuns.remove(sessionId);
            pendingDisconnects.remove(sessionId);
            terminalizingRuns.remove(sessionId);
            lifecycleTokens.remove(sessionId);
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
            pendingDisconnects.remove(sessionId);
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

    /** 创建标准 SSE emitter 并挂载到事件中枢。 */
    public SseEmitter createStream(String sessionId, String initialMessage, Long userId) {
        final long lifecycleToken;
        synchronized (lifecycleLock) {
            // The worker is scheduled after this method returns. Reserve the
            // session before creating the channel so two rapid requests cannot
            // replace each other's SSE channel before TaskRunService acquires its
            // durable run lock.
            if (pendingRuns.contains(sessionId)
                    || runningTasks.containsKey(sessionId)
                    || terminalizingRuns.contains(sessionId)
                    || eventCenter.hasRunningSession(sessionId)) {
                return createErrorEmitter("error", "该会话已有任务在运行");
            }
            pendingRuns.add(sessionId);
            lifecycleToken = lifecycleSequence.incrementAndGet();
            lifecycleTokens.put(sessionId, lifecycleToken);
        }
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        try {
            eventCenter.start(sessionId, initialMessage);
        } catch (RuntimeException e) {
            markIdle(sessionId);
            throw e;
        }
        registerDisconnectCancellation(sessionId, emitter, lifecycleToken);
        eventCenter.attachClient(sessionId, userId, emitter);
        return emitter;
    }

    /**
     * SSE 断连是执行控制信号，不只是 UI 事件。
     *
     * <p>浏览器关闭标签页、代理超时或网络断开时，Spring 只会回调 emitter；如果这里不
     * 传播取消，LLM、工具和子进程仍会继续运行并消耗配额。正常完成时
     * {@link #markCompleting(String)} 会先把连接转入终态，再发送 end/error，
     * 因此不会把已结束的任务误取消。</p>
     */
    private void registerDisconnectCancellation(String sessionId, SseEmitter emitter,
                                                long lifecycleToken) {
        AtomicBoolean signalled = new AtomicBoolean();
        Runnable cancelIfRunning = () -> {
            if (!signalled.compareAndSet(false, true)) {
                return;
            }
            boolean cancelNow;
            synchronized (lifecycleLock) {
                if (!Objects.equals(lifecycleTokens.get(sessionId), lifecycleToken)) {
                    return;
                }
                if (!pendingRuns.contains(sessionId) && !runningTasks.containsKey(sessionId)) {
                    return;
                }
                cancelNow = runningTasks.containsKey(sessionId);
                if (!cancelNow) {
                    // Worker has not called markRunning yet. Keep the request until
                    // executionControl.start has installed its real lease.
                    pendingDisconnects.add(sessionId);
                }
            }
            if (cancelNow && executionControl != null) {
                try {
                    executionControl.cancel(sessionId);
                    log.info("SSE 断连，已向执行控制面发送取消信号 session={}", sessionId);
                } catch (Exception e) {
                    log.warn("SSE 断连取消任务失败 session={}: {}", sessionId, e.getMessage());
                }
            }
        };
        emitter.onTimeout(cancelIfRunning);
        emitter.onCompletion(cancelIfRunning);
        emitter.onError(error -> cancelIfRunning.run());
    }

    public SessionEventCenter getEventCenter() {
        return eventCenter;
    }
}
