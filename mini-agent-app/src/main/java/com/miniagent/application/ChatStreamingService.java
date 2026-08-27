package com.miniagent.application;

import com.miniagent.agent.core.SessionEventCenter;
import com.miniagent.common.MessageConstants;
import com.miniagent.config.service.TaskRunService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 正在运行的任务：sessionId -> true。用于前端查询任务状态（内存快路径）。 */
    private final ConcurrentHashMap<String, Boolean> runningTasks = new ConcurrentHashMap<>();

    public void markRunning(String sessionId) {
        runningTasks.put(sessionId, Boolean.TRUE);
    }

    public void markIdle(String sessionId) {
        runningTasks.remove(sessionId);
    }

    public boolean isTaskRunning(String sessionId) {
        return runningTasks.containsKey(sessionId) || taskRunService.isRunning(sessionId);
    }

    public long getSseTimeoutMs() {
        return sseTimeoutMs;
    }

    /**
     * 重连到正在运行（或刚结束仍在缓冲期）的会话流。
     * 返回已挂载的 emitter；若无活动通道，发 "gone" 让前端回退到数据库加载。
     */
    public SseEmitter attachStream(String sessionId) {
        SseEmitter emitter = new SseEmitter(3600_000L);
        boolean ok = eventCenter.attachClient(sessionId, emitter);
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
    public SseEmitter createStream(String sessionId, String initialMessage) {
        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        eventCenter.start(sessionId, initialMessage);
        eventCenter.attachClient(sessionId, emitter);
        return emitter;
    }

    public SessionEventCenter getEventCenter() {
        return eventCenter;
    }
}
