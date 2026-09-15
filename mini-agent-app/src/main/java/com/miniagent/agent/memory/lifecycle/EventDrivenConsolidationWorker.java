package com.miniagent.agent.memory.lifecycle;

import com.miniagent.agent.memory.MemoryTaskLock;
import com.miniagent.agent.memory.entity.AgentEventEntity;
import com.miniagent.agent.memory.repository.AgentEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 异步巩固 Worker：定时处理未 processed 的事件。
 *
 * 场景：session 异常结束（崩溃、超时）时，consolidate() 可能没被调用。
 * 这个 worker 兜底处理这些遗留事件。
 */
@Component
public class EventDrivenConsolidationWorker {

    private static final Logger log = LoggerFactory.getLogger(EventDrivenConsolidationWorker.class);

    private static final String LOCK_NAME = "consolidation-worker";

    @Autowired
    private AgentEventRepository eventRepository;

    @Autowired
    private DefaultConsolidationService consolidationService;

    @Autowired
    private MemoryTaskLock taskLock;

    @Value("${agent.memory.consolidation.batch-size:50}")
    private int batchSize;

    /**
     * 每 10 分钟检查一次未处理的事件。
     *
     * <p>锁 TTL 取 9 分钟（短于调度间隔）—— 既覆盖单轮执行，又能在实例崩溃后
     * 自动过期，不会永久堵住其他实例。
     */
    @Scheduled(fixedDelayString = "${agent.memory.consolidation.interval-ms:600000}")
    public void processPendingEvents() {
        if (!taskLock.tryLock(LOCK_NAME, Duration.ofMinutes(9))) {
            log.debug("另有实例正在执行巩固，跳过本轮");
            return;
        }
        try {
            List<AgentEventEntity> unprocessed = eventRepository.findByProcessedFalseOrderByCreatedAtAsc();
            if (unprocessed.isEmpty()) {
                return;
            }

            log.info("发现 {} 条未处理事件，开始巩固...", unprocessed.size());

            // 按 sessionId 分组
            var bySession = unprocessed.stream()
                .limit(batchSize)
                .collect(Collectors.groupingBy(AgentEventEntity::getSessionId));

            for (var entry : bySession.entrySet()) {
                String sessionId = entry.getKey();
                if (sessionId == null) {
                    continue;
                }
                try {
                    consolidationService.consolidate(sessionId);
                } catch (Exception e) {
                    log.warn("巩固失败: session={}", sessionId, e);
                }
            }
        } finally {
            taskLock.unlock(LOCK_NAME);
        }
    }
}
