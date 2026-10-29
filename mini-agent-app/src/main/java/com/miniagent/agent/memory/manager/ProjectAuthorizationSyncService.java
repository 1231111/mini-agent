package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.MemoryTaskLock;
import com.miniagent.agent.memory.entity.AgentProjectAuthorizationOutboxEntity;
import com.miniagent.agent.memory.repository.AgentProjectAuthorizationOutboxRepository;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.memory.port.ProjectAuthorizationCommand;
import com.miniagent.memory.port.ProjectAuthorizationPort;
import com.miniagent.memory.port.ProjectAuthorizationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ProjectAuthorizationSyncService {
    private static final Logger log = LoggerFactory.getLogger(ProjectAuthorizationSyncService.class);
    private static final String LOCK_NAME = "project-authorization-outbox";
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 10;

    @Autowired private AgentProjectAuthorizationOutboxRepository outbox;
    @Autowired private ProcedureSettlementModeProperties mode;
    @Autowired private ObjectProvider<ProjectAuthorizationPort> portProvider;
    @Autowired private MemoryTaskLock taskLock;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;

    public void enqueue(String tenantId, String projectId, long ownerUserId, long version, List<Long> memberUserIds) {
        if (!mode.remote()) return;
        try {
            AgentProjectAuthorizationOutboxEntity item = outbox
                    .findByTenantIdAndProjectIdAndAuthorizationVersion(tenantId, projectId, version).orElse(null);
            String membersJson = objectMapper.writeValueAsString(memberUserIds == null ? List.of() : memberUserIds);
            if (item == null) {
                item = new AgentProjectAuthorizationOutboxEntity();
                item.setTenantId(tenantId);
                item.setProjectId(projectId);
                item.setAuthorizationVersion(version);
            }
            item.setOwnerUserId(ownerUserId);
            item.setMemberUserIdsJson(membersJson);
            item.setStatus(AgentProjectAuthorizationOutboxEntity.Status.PENDING);
            item.setAttempts(0);
            item.setNextAttemptAt(LocalDateTime.now());
            item.setLastError(null);
            outbox.save(item);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to enqueue project authorization snapshot", e);
        }
    }

    @Scheduled(fixedDelayString = "${agent.memory.procedure.outbox.interval-ms:5000}")
    public void drain() {
        ProjectAuthorizationPort port = portProvider.getIfAvailable();
        if (!mode.remote() || port == null || !taskLock.tryLock(LOCK_NAME, LOCK_TTL)) return;
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            List<AgentProjectAuthorizationOutboxEntity> processing = outbox.findByStatus(
                    AgentProjectAuthorizationOutboxEntity.Status.PROCESSING);
            processing.forEach(item -> {
                item.setStatus(AgentProjectAuthorizationOutboxEntity.Status.PENDING);
                item.setNextAttemptAt(LocalDateTime.now());
            });
            outbox.saveAll(processing);
            List<AgentProjectAuthorizationOutboxEntity> batch = outbox
                    .findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                            AgentProjectAuthorizationOutboxEntity.Status.PENDING, LocalDateTime.now());
            for (AgentProjectAuthorizationOutboxEntity selected : batch) {
                try {
                    tx.executeWithoutResult(status -> deliverOne(selected.getId(), port));
                } catch (Exception e) {
                    log.warn("Project authorization outbox item failed id={}", selected.getId(), e);
                }
            }
        } finally {
            taskLock.unlock(LOCK_NAME);
        }
    }

    private void deliverOne(Long id, ProjectAuthorizationPort port) {
        AgentProjectAuthorizationOutboxEntity item = outbox.findById(id).orElse(null);
        if (item == null) return;
        item.setStatus(AgentProjectAuthorizationOutboxEntity.Status.PROCESSING);
        try {
            List<Long> members = objectMapper.readValue(item.getMemberUserIdsJson(), new TypeReference<>() {});
            ProjectAuthorizationResult result = port.synchronize(new ProjectAuthorizationCommand(
                    item.getTenantId(), item.getProjectId(), item.getOwnerUserId(), members,
                    item.getAuthorizationVersion()));
            if (result == null || result.status() == ProjectAuthorizationResult.Status.CONFLICT) {
                item.setStatus(AgentProjectAuthorizationOutboxEntity.Status.FAILED);
                item.setLastError("Authorization snapshot conflict; remote version="
                        + (result == null ? "unknown" : result.currentVersion()));
            } else {
                // UPDATED/CREATED/UNCHANGED/STALE 均表示服务端已达到不低于该版本的状态。
                item.setStatus(AgentProjectAuthorizationOutboxEntity.Status.DONE);
                item.setLastError(null);
            }
        } catch (Exception e) {
            int attempts = item.getAttempts() + 1;
            item.setAttempts(attempts);
            item.setLastError(abbreviate(e.getMessage(), 1000));
            if (attempts >= MAX_ATTEMPTS) {
                item.setStatus(AgentProjectAuthorizationOutboxEntity.Status.FAILED);
            } else {
                item.setStatus(AgentProjectAuthorizationOutboxEntity.Status.PENDING);
                item.setNextAttemptAt(LocalDateTime.now().plusSeconds(Math.min(3600L, 1L << Math.min(12, attempts))));
            }
        }
        outbox.save(item);
    }

    private static String abbreviate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
