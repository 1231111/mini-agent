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

    @Value("${agent.memory.events.retention-days:90}")
    private int eventRetentionDays;

    @Value("${agent.memory.events.purge-batches-per-run:20}")
    private int purgeBatchesPerRun;

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
            int quarantined = eventRepository.markUnconsolidatableProcessed();
            if (quarantined > 0) {
                log.warn("Marked {} legacy memory events without a tenant/session as non-consolidatable", quarantined);
            }
            List<AgentEventEntity> unprocessed = eventRepository
                    .findTop50ByProcessedFalseAndSessionIdIsNotNullAndTenantIdIsNotNullOrderByCreatedAtAsc();
            if (unprocessed.isEmpty()) {
                return;
            }

            log.info("发现 {} 条未处理事件，开始巩固...", unprocessed.size());

            var batches = unprocessed.stream().limit(batchSize).collect(Collectors.toList());
            var byTask = batches.stream()
                    .filter(event -> event.getSessionId() != null
                            && event.getTaskId() != null && !event.getTaskId().isBlank())
                    .collect(Collectors.groupingBy(event -> event.getTenantId() + "\u0000"
                            + event.getSessionId() + "\u0000" + event.getTaskId()));
            for (List<AgentEventEntity> taskEvents : byTask.values()) {
                AgentEventEntity first = taskEvents.get(0);
                try {
                    consolidationService.consolidateTask(
                            first.getTenantId(), first.getSessionId(), first.getTaskId());
                } catch (Exception e) {
                    log.warn("任务经历巩固失败: session={}, task={}", first.getSessionId(), first.getTaskId(), e);
                }
            }
            var legacyGroups = batches.stream()
                    .filter(event -> event.getSessionId() != null && event.getTenantId() != null
                            && (event.getTaskId() == null || event.getTaskId().isBlank()))
                    .collect(Collectors.groupingBy(event -> event.getTenantId() + "\u0000" + event.getSessionId()));
            for (List<AgentEventEntity> legacyEvents : legacyGroups.values()) {
                AgentEventEntity first = legacyEvents.get(0);
                try {
                    consolidationService.consolidate(first.getTenantId(), first.getSessionId());
                } catch (Exception e) {
                    log.warn("旧版会话经历巩固失败: tenant={}, session={}",
                            first.getTenantId(), first.getSessionId(), e);
                }
            }
        } finally {
            taskLock.unlock(LOCK_NAME);
        }
    }

    /**
     * Processed events already have a durable Episode (or were explicitly
     * quarantined because they lack an owner/session). Keep a bounded 90-day
     * operational history and delete in small batches to avoid a single table
     * lock or large persistence-context load.
     */
    @Scheduled(cron = "${agent.memory.events.purge-cron:0 15 3 * * *}")
    public void purgeOldProcessedEvents() {
        if (!taskLock.tryLock(LOCK_NAME, Duration.ofMinutes(9))) {
            log.debug("另有实例正在执行记忆事件处理，跳过保留期清理");
            return;
        }
        try {
            int batches = 0;
            int total = 0;
            var cutoff = java.time.LocalDateTime.now().minusDays(Math.max(1, eventRetentionDays));
            while (batches < Math.max(1, purgeBatchesPerRun)) {
                List<AgentEventEntity> old = eventRepository
                        .findTop500ByProcessedTrueAndCreatedAtBeforeOrderByIdAsc(cutoff);
                if (old.isEmpty()) {
                    break;
                }
                int deleted = eventRepository.deleteByIds(
                        old.stream().map(AgentEventEntity::getId).toList());
                if (deleted == 0) {
                    break;
                }
                total += deleted;
                batches++;
            }
            if (total > 0) {
                log.info("Purged {} processed memory events older than {} days", total,
                        Math.max(1, eventRetentionDays));
            }
        } finally {
            taskLock.unlock(LOCK_NAME);
        }
    }
}
