package com.miniagent.agent.trace;

import com.miniagent.config.repository.AgentTraceStepRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Keeps the operational trace bounded without touching the durable chat/task
 * records. A failed cleanup is logged and retried on the next schedule; it
 * must never prevent the agent from serving a request.
 */
@Service
@Slf4j
public class TraceRetentionService {

    private final AgentTraceStepRepository repository;

    @Value("${agent.observability.trace-retention-days:30}")
    private int retentionDays = 30;

    public TraceRetentionService(AgentTraceStepRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${agent.observability.trace-cleanup-interval-ms:21600000}")
    @Transactional
    public void purgeExpired() {
        int days = Math.max(1, retentionDays);
        LocalDateTime cutoff = LocalDateTime.now().minusDays(days);
        try {
            long deleted = repository.deleteByCreatedAtBefore(cutoff);
            if (deleted > 0) {
                log.info("trace retention cleanup deleted={} cutoff={} retentionDays={}",
                        deleted, cutoff, days);
            }
        } catch (RuntimeException e) {
            // Observability maintenance is best effort. Do not take the
            // serving path down because a replica is temporarily unavailable.
            log.warn("trace retention cleanup failed cutoff={}: {}", cutoff, e.getMessage());
        }
    }

    int effectiveRetentionDays() {
        return Math.max(1, retentionDays);
    }
}
