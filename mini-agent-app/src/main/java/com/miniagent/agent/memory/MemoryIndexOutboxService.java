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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Transactional Outbox：索引故障不会回滚主记忆，且可跨进程重启重试。 */
@Service
public class MemoryIndexOutboxService {
    private static final Logger log = LoggerFactory.getLogger(MemoryIndexOutboxService.class);
    private static final int MAX_ATTEMPTS = 10;

    private final AgentMemoryIndexOutboxRepository outboxRepository;
    private final AgentMemoryEntryRepository memoryRepository;
    private final MilvusHybridSearchEngine milvus;

    public MemoryIndexOutboxService(AgentMemoryIndexOutboxRepository outboxRepository,
                                    AgentMemoryEntryRepository memoryRepository,
                                    ObjectProvider<MilvusHybridSearchEngine> milvusProvider) {
        this.outboxRepository = outboxRepository;
        this.memoryRepository = memoryRepository;
        this.milvus = milvusProvider.getIfAvailable();
    }

    @PostConstruct
    void recoverInterruptedItems() {
        List<AgentMemoryIndexOutboxEntity> interrupted = outboxRepository.findByStatus(
                AgentMemoryIndexOutboxEntity.Status.PROCESSING);
        for (AgentMemoryIndexOutboxEntity item : interrupted) {
            item.setStatus(AgentMemoryIndexOutboxEntity.Status.PENDING);
            item.setNextAttemptAt(LocalDateTime.now());
        }
        if (!interrupted.isEmpty()) outboxRepository.saveAll(interrupted);
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
        if (milvus == null || memoryId == null) return;
        AgentMemoryIndexOutboxEntity item = new AgentMemoryIndexOutboxEntity();
        item.setMemoryId(memoryId);
        item.setOperation(operation);
        item.setStatus(AgentMemoryIndexOutboxEntity.Status.PENDING);
        item.setNextAttemptAt(LocalDateTime.now());
        outboxRepository.save(item);
    }

    @Scheduled(fixedDelayString = "${agent.memory.index-outbox.interval-ms:5000}")
    @Transactional
    public void drain() {
        if (milvus == null) return;
        List<AgentMemoryIndexOutboxEntity> batch = outboxRepository
                .findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                        AgentMemoryIndexOutboxEntity.Status.PENDING, LocalDateTime.now());
        for (AgentMemoryIndexOutboxEntity item : batch) process(item);
    }

    private void process(AgentMemoryIndexOutboxEntity item) {
        item.setStatus(AgentMemoryIndexOutboxEntity.Status.PROCESSING);
        outboxRepository.save(item);
        try {
            if (item.getOperation() == AgentMemoryIndexOutboxEntity.Operation.DELETE) {
                if (!milvus.delete(item.getMemoryId())) throw new IllegalStateException("Milvus delete failed");
            } else {
                AgentMemoryEntryEntity memory = memoryRepository.findById(item.getMemoryId()).orElse(null);
                if (memory == null || memory.getStatus() != AgentMemoryEntryEntity.Status.ACTIVE) {
                    if (!milvus.delete(item.getMemoryId())) throw new IllegalStateException("Milvus delete failed");
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
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
