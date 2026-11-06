package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.MemoryTaskLock;
import com.miniagent.agent.memory.config.ConditionalOnCloudMemory;
import com.miniagent.agent.memory.entity.AgentOutcomeOutboxEntity;
import com.miniagent.agent.memory.repository.AgentOutcomeOutboxRepository;
import com.miniagent.memory.contract.MemoryApi.AckStatus;
import com.miniagent.memory.contract.MemoryApi.OutcomeAck;
import com.miniagent.memory.contract.MemoryApi.OutcomeBatchResult;
import com.miniagent.memory.contract.MemoryApi.OutcomeEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 节点终态写入本地 outbox，后台批量投递 memory-service。写失败只记日志，不打断用户任务。
 */
@Service
@ConditionalOnCloudMemory
public class OutcomeOutboxService {
    private static final Logger log = LoggerFactory.getLogger(OutcomeOutboxService.class);
    static final String METRIC_LAG = "memory_outbox_lag_seconds";
    private static final String LOCK_NAME = "memory-outcome-outbox";
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final int MAX_ATTEMPTS = 10;
    private static final int MAX_ERROR = 1000;
    private static final int DEFAULT_BATCH = 500;

    private final AgentOutcomeOutboxRepository outbox;
    private final CloudMemoryClient client;
    private final ObjectMapper json;
    private final MemoryTaskLock taskLock;
    private final PlatformTransactionManager transactions;
    private final AtomicLong lagSeconds = new AtomicLong();

    public OutcomeOutboxService(AgentOutcomeOutboxRepository outbox, CloudMemoryClient client,
                                ObjectMapper json, MemoryTaskLock taskLock,
                                PlatformTransactionManager transactions, MeterRegistry metrics) {
        this.outbox = outbox;
        this.client = client;
        this.json = json;
        this.taskLock = taskLock;
        this.transactions = transactions;
        metrics.gauge(METRIC_LAG, lagSeconds);
    }

    /** 同事务写入；eventId 冲突视为已入队。 */
    @Transactional
    public void enqueue(OutcomeEvent event) {
        if (event == null || event.eventId() == null || event.tenantId() == null) {
            return;
        }
        if (outbox.findByEventId(event.eventId()).isPresent()) {
            return;
        }
        try {
            AgentOutcomeOutboxEntity row = new AgentOutcomeOutboxEntity();
            row.setEventId(event.eventId());
            row.setTenantId(event.tenantId());
            row.setPayloadJson(json.writeValueAsString(event));
            row.setStatus(AgentOutcomeOutboxEntity.Status.PENDING);
            row.setNextAttemptAt(LocalDateTime.now());
            outbox.save(row);
        } catch (DataIntegrityViolationException duplicate) {
            log.debug("结果事件已在队列中: {}", event.eventId());
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("结果事件入队失败 event={}: {}", event.eventId(), e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${agent.memory.outcome-outbox.interval-ms:2000}")
    public void drain() {
        if (!taskLock.tryLock(LOCK_NAME, LOCK_TTL)) {
            return;
        }
        try {
            TransactionTemplate tx = new TransactionTemplate(transactions);
            tx.executeWithoutResult(s -> outbox.recoverExpired(
                    AgentOutcomeOutboxEntity.Status.PROCESSING,
                    AgentOutcomeOutboxEntity.Status.PENDING, LocalDateTime.now()));
            refreshLag();
            List<Long> due = outbox.findTop500ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                    AgentOutcomeOutboxEntity.Status.PENDING, LocalDateTime.now())
                    .stream().limit(DEFAULT_BATCH).map(AgentOutcomeOutboxEntity::getId).toList();
            Map<String, List<Claimed>> byTenant = new LinkedHashMap<>();
            for (Long id : due) {
                Claimed claimed = claim(tx, id);
                if (claimed != null) {
                    byTenant.computeIfAbsent(claimed.row().getTenantId(), k -> new ArrayList<>())
                            .add(claimed);
                }
            }
            byTenant.forEach(this::deliver);
        } finally {
            taskLock.unlock(LOCK_NAME);
        }
    }

    private void deliver(String tenantId, List<Claimed> batch) {
        TransactionTemplate tx = new TransactionTemplate(transactions);
        List<OutcomeEvent> events = new ArrayList<>();
        for (Claimed item : batch) {
            try {
                events.add(json.readValue(item.row().getPayloadJson(), OutcomeEvent.class));
            } catch (JsonProcessingException e) {
                fail(tx, item, "payload 无法解析");
            }
        }
        if (events.isEmpty()) {
            return;
        }
        try {
            OutcomeBatchResult result = client.submitOutcomes(tenantId, events);
            Map<String, AckStatus> byId = new LinkedHashMap<>();
            if (result != null && result.results() != null) {
                for (OutcomeAck ack : result.results()) {
                    byId.put(ack.eventId(), ack.status());
                }
            }
            for (Claimed item : batch) {
                AckStatus status = byId.get(item.row().getEventId());
                if (status == AckStatus.ACCEPTED || status == AckStatus.DUPLICATE) {
                    tx.executeWithoutResult(s -> outbox.complete(item.row().getId(),
                            AgentOutcomeOutboxEntity.Status.PROCESSING,
                            AgentOutcomeOutboxEntity.Status.DONE, item.token()));
                } else if (status == AckStatus.REJECTED) {
                    fail(tx, item, "被记忆服务拒绝");
                } else {
                    fail(tx, item, "回执缺失");
                }
            }
        } catch (RuntimeException e) {
            batch.forEach(item -> fail(tx, item, e.getMessage()));
        }
    }

    private Claimed claim(TransactionTemplate tx, Long id) {
        String token = UUID.randomUUID().toString();
        return tx.execute(s -> {
            LocalDateTime now = LocalDateTime.now();
            if (outbox.claim(id, AgentOutcomeOutboxEntity.Status.PENDING,
                    AgentOutcomeOutboxEntity.Status.PROCESSING, now, now.plus(LEASE), token) != 1) {
                return null;
            }
            return outbox.findById(id).map(row -> new Claimed(row, token)).orElse(null);
        });
    }

    private void fail(TransactionTemplate tx, Claimed item, String error) {
        tx.executeWithoutResult(s -> {
            int attempts = item.row().getAttempts() + 1;
            AgentOutcomeOutboxEntity.Status next = attempts >= MAX_ATTEMPTS
                    ? AgentOutcomeOutboxEntity.Status.FAILED
                    : AgentOutcomeOutboxEntity.Status.PENDING;
            LocalDateTime when = next == AgentOutcomeOutboxEntity.Status.PENDING
                    ? LocalDateTime.now().plusSeconds(Math.min(300, 1L << Math.min(attempts, 8)))
                    : LocalDateTime.now();
            String clipped = error == null ? "" : error.substring(0, Math.min(MAX_ERROR, error.length()));
            outbox.failAttempt(item.row().getId(), AgentOutcomeOutboxEntity.Status.PROCESSING,
                    next, attempts, when, clipped, item.token());
        });
    }

    private void refreshLag() {
        LocalDateTime oldest = outbox.findFirstByStatusOrderByCreatedAtAsc(
                AgentOutcomeOutboxEntity.Status.PENDING)
                .map(AgentOutcomeOutboxEntity::getCreatedAt).orElse(null);
        if (oldest == null) {
            lagSeconds.set(0);
            return;
        }
        lagSeconds.set(Math.max(0, Duration.between(oldest, LocalDateTime.now()).getSeconds()));
    }

    private record Claimed(AgentOutcomeOutboxEntity row, String token) {
    }
}
