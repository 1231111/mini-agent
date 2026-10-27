package com.miniagent.agent.memory.lifecycle;

import com.miniagent.agent.memory.MemoryTaskLock;
import com.miniagent.agent.memory.repository.AgentMemoryEntryRepository;
import com.miniagent.memory.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** Runs retention evaluation for active tenants without overlapping scheduler instances. */
@Component
public class MemoryForgettingWorker {
    private static final Logger log = LoggerFactory.getLogger(MemoryForgettingWorker.class);
    private static final String LOCK_NAME = "memory-forgetting-worker";
    private static final Duration LOCK_TTL = Duration.ofHours(6);

    @Autowired
    private AgentMemoryEntryRepository entryRepository;

    @Autowired
    private MemoryManager memoryManager;

    @Autowired
    private MemoryTaskLock taskLock;

    @Scheduled(fixedDelayString = "${agent.memory.forget.interval-ms:86400000}")
    public void forget() {
        if (!taskLock.tryLock(LOCK_NAME, LOCK_TTL)) {
            log.debug("另有实例正在执行记忆遗忘，跳过本轮");
            return;
        }
        try {
            List<String> tenantIds = entryRepository.findDistinctActiveTenantIds();
            for (String tenantId : tenantIds) {
                if (tenantId == null || tenantId.isBlank()) {
                    continue;
                }
                try {
                    memoryManager.forget(tenantId);
                } catch (Exception e) {
                    log.warn("租户记忆遗忘失败，保留后续租户处理: tenantId={}", tenantId, e);
                }
            }
        } catch (Exception e) {
            log.error("查询待遗忘记忆租户失败，本轮终止", e);
        } finally {
            taskLock.unlock(LOCK_NAME);
        }
    }
}
