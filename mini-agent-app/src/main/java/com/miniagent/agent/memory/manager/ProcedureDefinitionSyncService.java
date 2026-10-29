package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.MemoryTaskLock;
import com.miniagent.agent.memory.entity.AgentProcedureDefinitionOutboxEntity;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.repository.AgentProcedureDefinitionOutboxRepository;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.memory.port.MemoryPrincipal;
import com.miniagent.memory.port.ProcedureDefinitionCommand;
import com.miniagent.memory.port.ProcedureDefinitionPort;
import com.miniagent.memory.port.ProcedureDefinitionResult;
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

/**
 * 把执行侧写入的 SOP 版本可靠地投影到独立记忆服务。
 *
 * <p><b>为什么不用一次同步 HTTP 调用解决</b>：投影失败不是「少一条日志」，而是这条 SOP
 * 之后所有结算都会变成 {@code STALE_PROCEDURE}——任务照常成功，成功率永远不动。
 * 同步调用在写入线程里失败既不能重试（写入已提交），也不能回滚（本轮事务已结束）。
 * 因此采用 outbox：写入与新版本快照同事务落库，后台按版本顺序投递，失败退避重试，
 * 超过上限保留 {@code FAILED} 行和最后错误，可人工核对。</p>
 *
 * <p><b>local 模式下完全不参与</b>：只有 {@code agent.memory.service.mode=remote} 才写队列。
 * 桌面档仍是本地结算，多写一张表只是无意义的开销。</p>
 */
@Service
public class ProcedureDefinitionSyncService {
    private static final Logger log = LoggerFactory.getLogger(ProcedureDefinitionSyncService.class);
    private static final int MAX_ATTEMPTS = 10;
    private static final String LOCK_NAME = "procedure-definition-outbox";
    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    @Autowired
    private AgentProcedureDefinitionOutboxRepository outbox;
    @Autowired
    private ProcedureSettlementModeProperties mode;
    @Autowired
    private ObjectProvider<ProcedureDefinitionPort> definitionPort;
    @Autowired
    private MemoryTaskLock taskLock;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 在调用方事务内登记一次投影。
     *
     * <p>刻意不加 {@code @Transactional}：它必须加入写入 SOP 的那个事务。
     * 若这里自己开新事务，SOP 回滚时快照会留下，之后投递一个不存在的版本。</p>
     */
    public void enqueue(AgentProcedureEntity entity) {
        if (!mode.remote() || entity == null || entity.getId() == null) {
            return;
        }
        Long userId = OwnerContext.currentUserId();
        if (userId == null || entity.getTenantId() == null || entity.getTenantId().isBlank()) {
            // 没有可信身份就无法构造投影请求。此时本地写入仍然有效（local 读路径照常用），
            // 但这条 SOP 在远端不存在，结算会被判为过期版本，所以必须留下可见记录。
            log.warn("SOP 缺少租户或用户身份，跳过远端投影: procedureId={}, tenant={}",
                    entity.getId(), entity.getTenantId());
            return;
        }
        int version = entity.getProcedureVersion() == null ? 1 : entity.getProcedureVersion();
        AgentProcedureDefinitionOutboxEntity existing = outbox
                .findByTenantIdAndProcedureIdAndProcedureVersion(entity.getTenantId(), entity.getId(), version)
                .orElse(null);
        if (existing != null) {
            // 同一版本再次登记只有一种来源：内容没变、只改了权重或名字拼写（不升版）。
            // 已投递成功的记录要刷新快照再投一次，否则远端那份元数据永远停在首次投影。
            // 仍在队列中（PENDING/PROCESSING）或已冲突失败（FAILED）的不动：前者本来就会带上新快照，
            // 后者需要人工确认，自动复活会让冲突被悄悄掩盖。
            if (existing.getStatus() == AgentProcedureDefinitionOutboxEntity.Status.DONE) {
                applySnapshot(existing, entity, userId);
                existing.setStatus(AgentProcedureDefinitionOutboxEntity.Status.PENDING);
                existing.setAttempts(0);
                existing.setNextAttemptAt(LocalDateTime.now());
                outbox.save(existing);
            }
            return;
        }
        AgentProcedureDefinitionOutboxEntity item = new AgentProcedureDefinitionOutboxEntity();
        item.setProcedureId(entity.getId());
        item.setProcedureVersion(version);
        item.setStatus(AgentProcedureDefinitionOutboxEntity.Status.PENDING);
        item.setNextAttemptAt(LocalDateTime.now());
        applySnapshot(item, entity, userId);
        outbox.save(item);
    }

    /** 把一行 SOP 当前的样子写进快照。写入与刷新共用，避免两处字段清单漂移。 */
    private void applySnapshot(AgentProcedureDefinitionOutboxEntity item, AgentProcedureEntity entity, Long userId) {
        item.setTenantId(entity.getTenantId());
        item.setUserId(userId);
        item.setScopeType(entity.getScopeType().name());
        item.setScopeId(entity.getScopeId());
        item.setProjectId("PROJECT".equals(entity.getScopeType().name())
                ? entity.getScopeId() : OwnerContext.currentProjectId());
        item.setName(entity.getName());
        item.setDescription(entity.getDescription());
        item.setPreconditionsJson(entity.getPreconditionsJson());
        item.setStepsJson(entity.getStepsJson());
        item.setSuccessConditionsJson(entity.getSuccessConditionsJson());
        item.setImportance(entity.getImportance());
        item.setProcedureStatus(statusName(entity.getStatus()));
    }

    /**
     * 投递到期记录。
     *
     * <p>每条一个独立事务：整批一个事务会让一条失败回滚整批，且「已投递」的状态
     * 在解锁之后才提交，另一个实例会重复投同一批。</p>
     */
    @Scheduled(fixedDelayString = "${agent.memory.procedure.outbox.interval-ms:5000}")
    public void drain() {
        ProcedureDefinitionPort port = definitionPort.getIfAvailable();
        if (!mode.remote() || port == null) {
            return;
        }
        if (!taskLock.tryLock(LOCK_NAME, LOCK_TTL)) {
            log.debug("另有实例正在投递 SOP 投影，跳过本轮");
            return;
        }
        try {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            recoverStuck();
            List<AgentProcedureDefinitionOutboxEntity> batch = outbox
                    .findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
                            AgentProcedureDefinitionOutboxEntity.Status.PENDING, LocalDateTime.now());
            for (AgentProcedureDefinitionOutboxEntity item : batch) {
                try {
                    tx.executeWithoutResult(status -> deliverOne(item.getId(), port));
                } catch (Exception e) {
                    log.warn("SOP 投影单条处理异常 id={}", item.getId(), e);
                }
            }
        } finally {
            taskLock.unlock(LOCK_NAME);
        }
    }

    /** 进程在投递途中退出会把记录留在 PROCESSING，重启后必须放回队列，否则永久卡住。 */
    private void recoverStuck() {
        List<AgentProcedureDefinitionOutboxEntity> stuck = outbox.findByStatus(
                AgentProcedureDefinitionOutboxEntity.Status.PROCESSING);
        if (stuck.isEmpty()) {
            return;
        }
        for (AgentProcedureDefinitionOutboxEntity item : stuck) {
            item.setStatus(AgentProcedureDefinitionOutboxEntity.Status.PENDING);
            item.setNextAttemptAt(LocalDateTime.now());
        }
        outbox.saveAll(stuck);
    }

    private void deliverOne(Long itemId, ProcedureDefinitionPort port) {
        AgentProcedureDefinitionOutboxEntity item = outbox.findById(itemId).orElse(null);
        if (item == null) {
            return;
        }
        item.setStatus(AgentProcedureDefinitionOutboxEntity.Status.PROCESSING);
        try {
            ProcedureDefinitionResult result = port.synchronize(toCommand(item));
            if (result != null && result.status() == ProcedureDefinitionResult.Status.CONFLICT) {
                // 同版本不同内容，或 ID 被别的归属占用。自动覆盖任一方都会让统计失去口径，
                // 所以停在 FAILED 等人看，不重试、不吞掉。
                item.setStatus(AgentProcedureDefinitionOutboxEntity.Status.FAILED);
                item.setLastError("投影冲突：服务端版本 " + result.currentVersion());
                log.error("SOP 投影冲突，已停止重试: procedureId={}, version={}, 服务端版本={}",
                        item.getProcedureId(), item.getProcedureVersion(), result.currentVersion());
            } else {
                item.setStatus(AgentProcedureDefinitionOutboxEntity.Status.DONE);
                item.setLastError(null);
            }
        } catch (Exception e) {
            int attempts = item.getAttempts() + 1;
            item.setAttempts(attempts);
            item.setLastError(abbreviate(e.getMessage(), 1000));
            if (attempts >= MAX_ATTEMPTS) {
                item.setStatus(AgentProcedureDefinitionOutboxEntity.Status.FAILED);
                log.error("SOP 投影永久失败 procedureId={} version={}: {}",
                        item.getProcedureId(), item.getProcedureVersion(), e.getMessage());
            } else {
                item.setStatus(AgentProcedureDefinitionOutboxEntity.Status.PENDING);
                long delaySeconds = Math.min(3600L, 1L << Math.min(12, attempts));
                item.setNextAttemptAt(LocalDateTime.now().plusSeconds(delaySeconds));
            }
        }
        outbox.save(item);
    }

    private ProcedureDefinitionCommand toCommand(AgentProcedureDefinitionOutboxEntity item) {
        return new ProcedureDefinitionCommand(
                new MemoryPrincipal(item.getTenantId(), item.getUserId(), item.getProjectId(), null),
                item.getProcedureId(),
                item.getProcedureVersion(),
                item.getScopeType(),
                item.getScopeId(),
                item.getName(),
                item.getDescription(),
                item.getPreconditionsJson(),
                item.getStepsJson(),
                item.getSuccessConditionsJson(),
                item.getImportance() == null ? 0.5 : item.getImportance(),
                item.getProcedureStatus());
    }

    private static String statusName(AgentMemoryEntryEntity.Status status) {
        return status == null ? AgentMemoryEntryEntity.Status.ACTIVE.name() : status.name();
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
