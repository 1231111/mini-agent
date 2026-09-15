package com.miniagent.agent.memory;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentMemoryIndexOutboxEntity;
import com.miniagent.agent.memory.repository.AgentMemoryEntryRepository;
import com.miniagent.agent.memory.repository.AgentMemoryIndexOutboxRepository;
import com.miniagent.agent.memory.retriever.MemoryEntryMapper;
import com.miniagent.agent.memory.retriever.MilvusHybridSearchEngine;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** Transactional Outbox：索引故障不会回滚主记忆，且可跨进程重启重试。 */
@Service
public class MemoryIndexOutboxService {
    private static final Logger log = LoggerFactory.getLogger(MemoryIndexOutboxService.class);
    private static final int MAX_ATTEMPTS = 10;

    /** 与 {@code EventDrivenConsolidationWorker} 共用 {@link MemoryTaskLock} 实现跨实例互斥。 */
    private static final String LOCK_NAME = "memory-index-outbox";

    /**
     * 锁 TTL 取 5 分钟：远大于单批 50 条的常规耗时（亚秒级），
     * 同时在实例崩溃后自动过期，不会把队列永久堵死。
     */
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    @Autowired
    private AgentMemoryIndexOutboxRepository outboxRepository;
    @Autowired
    private AgentMemoryEntryRepository memoryRepository;
    @Autowired
    private ObjectProvider<MilvusHybridSearchEngine> milvusProvider;
    @Autowired
    private MemoryTaskLock taskLock;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private MilvusHybridSearchEngine milvus;

    /**
     * 每条 outbox 记录一个独立事务。
     *
     * <p>不能直接用 {@code @Transactional} 标注 {@code process()}：它是类内自调用，
     * 不经过 Spring 事务代理，注解会静默失效。故此处用编程式事务。
     */
    private TransactionTemplate txTemplate;

    @PostConstruct
    void init() {
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.milvus = milvusProvider.getIfAvailable();
        // recover interrupted items
        List<AgentMemoryIndexOutboxEntity> interrupted = outboxRepository.findByStatus(
                AgentMemoryIndexOutboxEntity.Status.PROCESSING);
        for (AgentMemoryIndexOutboxEntity item : interrupted) {
            item.setStatus(AgentMemoryIndexOutboxEntity.Status.PENDING);
            item.setNextAttemptAt(LocalDateTime.now());
        }
        if (!interrupted.isEmpty()) {
            outboxRepository.saveAll(interrupted);
        }
    }

    @Transactional
    public void enqueueUpsert(Long memoryId) {
        enqueue(memoryId, AgentMemoryIndexOutboxEntity.Operation.UPSERT);
    }

    @Transactional
    public void enqueueDelete(Long memoryId) {
        enqueue(memoryId, AgentMemoryIndexOutboxEntity.Operation.DELETE);
    }

    private void enqueue(Long memoryId, AgentMemoryIndexOutboxEntity.Operation operation) {
        if (milvus == null || memoryId == null) {
            return;
        }
        AgentMemoryIndexOutboxEntity item = new AgentMemoryIndexOutboxEntity();
        item.setMemoryId(memoryId);
        item.setOperation(operation);
        item.setStatus(AgentMemoryIndexOutboxEntity.Status.PENDING);
        item.setNextAttemptAt(LocalDateTime.now());
        outboxRepository.save(item);
    }

    /**
     * 拉取并投递一批 PENDING 记录。
     *
     * <p><b>为什么整批不再套一个 {@code @Transactional}</b>：原实现里事务由
     * {@code drain()} 统一开启，而 {@code unlock()} 在 {@code finally} 中执行——
     * 解锁发生在方法返回<b>之前</b>，事务提交发生在方法返回<b>之后</b>。
     * 于是存在窗口：实例 A 已解锁但尚未提交，实例 B 拿到锁后查 PENDING，
     * 看到的仍是 A 修改前的旧状态，于是重复投递同一批记录。
     *
     * <p>改为每条一个短事务后，状态流转在解锁前就已落库，窗口消失；
     * 同时避免长事务长时间占用连接。
     */
    @Scheduled(fixedDelayString = "${agent.memory.index-outbox.interval-ms:5000}")
    public void drain() {
        if (milvus == null) {
            return;
        }
        if (!taskLock.tryLock(LOCK_NAME, LOCK_TTL)) {
            log.debug("另有实例正在投递记忆索引 Outbox，跳过本轮");
            return;
        }
        try {
            List<AgentMemoryIndexOutboxEntity> batch = outboxRepository
                    .findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                            AgentMemoryIndexOutboxEntity.Status.PENDING, LocalDateTime.now());
            for (AgentMemoryIndexOutboxEntity item : batch) {
                try {
                    txTemplate.executeWithoutResult(status -> process(item.getId()));
                } catch (Exception e) {
                    // 单条失败不拖垮整批；process 内部已处理业务异常，这里兜住事务/连接层异常
                    log.warn("索引 Outbox 单条处理异常 id={}", item.getId(), e);
                }
            }
        } finally {
            taskLock.unlock(LOCK_NAME);
        }
    }

    /**
     * 在独立事务内处理单条记录。
     *
     * <p>入参是 id 而非实体：实体是在上一段（无事务的）查询里加载的，早已脱离持久化上下文，
     * 在事务内重新加载才能拿到当前行状态并让脏检查生效。
     */
    private void process(Long itemId) {
        AgentMemoryIndexOutboxEntity item = outboxRepository.findById(itemId).orElse(null);
        if (item == null) {
            return;
        }
        item.setStatus(AgentMemoryIndexOutboxEntity.Status.PROCESSING);
        try {
            if (item.getOperation() == AgentMemoryIndexOutboxEntity.Operation.DELETE) {
                if (!milvus.delete(item.getMemoryId())) {
                    throw new IllegalStateException("Milvus delete failed");
                }
            } else {
                AgentMemoryEntryEntity memory = memoryRepository.findById(item.getMemoryId()).orElse(null);
                if (memory == null || memory.getStatus() != AgentMemoryEntryEntity.Status.ACTIVE) {
                    if (!milvus.delete(item.getMemoryId())) {
                        throw new IllegalStateException("Milvus delete failed");
                    }
                } else {
                    if (!milvus.upsert(MemoryEntryMapper.fromEntity(memory)))
                        throw new IllegalStateException("Milvus upsert failed");
                }
            }
            item.setStatus(AgentMemoryIndexOutboxEntity.Status.DONE);
            item.setLastError(null);
        } catch (Exception e) {
            int attempts = item.getAttempts() + 1;
            item.setAttempts(attempts);
            item.setLastError(abbreviate(e.getMessage(), 1000));
            if (attempts >= MAX_ATTEMPTS) {
                item.setStatus(AgentMemoryIndexOutboxEntity.Status.FAILED);
                log.error("记忆索引 Outbox 永久失败 memoryId={} op={}: {}",
                        item.getMemoryId(), item.getOperation(), e.getMessage());
            } else {
                item.setStatus(AgentMemoryIndexOutboxEntity.Status.PENDING);
                long delaySeconds = Math.min(3600L, 1L << Math.min(12, attempts));
                item.setNextAttemptAt(LocalDateTime.now().plusSeconds(delaySeconds));
            }
        }
        outboxRepository.save(item);
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
