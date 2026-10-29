package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真 H2 验证自动归档阈值与版本/状态 CAS。
 *
 * <p>不能只测 service mock：这条治理的安全性就在数据库 UPDATE 的 where 条件里，
 * 只有真库才能证明「样本/比例/版本/状态任一不符都不归档」。</p>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:procedure-governance;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
class ProcedureGovernancePersistenceTest {

    @Autowired
    private AgentProcedureRepository procedures;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void archivesOnlyWhenSampleCountAndFailureThresholdAreBothMet() {
        Long candidate = persist("candidate", 10, 0.20, 3, AgentMemoryEntryEntity.Status.ACTIVE);
        Long tooFew = persist("too-few", 9, 0.0, 1, AgentMemoryEntryEntity.Status.ACTIVE);
        Long aboveThreshold = persist("above", 10, 0.21, 1, AgentMemoryEntryEntity.Status.ACTIVE);
        Long alreadyArchived = persist("archived", 100, 0.0, 1, AgentMemoryEntryEntity.Status.ARCHIVED);

        ProcedureGovernanceProperties properties = new ProcedureGovernanceProperties();
        properties.setMinAttempts(10);
        properties.setArchiveMaxSuccessRate(0.20);
        ProcedureGovernanceService service = new ProcedureGovernanceService(procedures, properties);

        int count = inTransaction(() -> service.archiveUnderperforming("tenant-a", 9L));

        assertEquals(1, count, "只有样本数达标且成功率恰好在边界的 ACTIVE 行被归档");
        assertEquals(AgentMemoryEntryEntity.Status.ARCHIVED, reload(candidate).getStatus());
        assertEquals(AgentMemoryEntryEntity.Status.ACTIVE, reload(tooFew).getStatus(), "样本不足不归档");
        assertEquals(AgentMemoryEntryEntity.Status.ACTIVE, reload(aboveThreshold).getStatus(), "高于阈值不归档");
        assertEquals(AgentMemoryEntryEntity.Status.ARCHIVED, reload(alreadyArchived).getStatus(), "不会重复归档");
    }

    @Test
    void governanceIsIsolatedByTenantAndOwner() {
        Long sameOwnerWrongTenant = persist("tenant-b", "9", "wrong-tenant", 20, 0.0, 1,
                AgentMemoryEntryEntity.Status.ACTIVE);
        Long wrongOwner = persist("tenant-a", "10", "wrong-owner", 20, 0.0, 1,
                AgentMemoryEntryEntity.Status.ACTIVE);
        Long rightOwner = persist("tenant-a", "9", "right-owner", 20, 0.0, 1,
                AgentMemoryEntryEntity.Status.ACTIVE);

        ProcedureGovernanceService service = new ProcedureGovernanceService(procedures,
                properties(10, 0.20));
        int count = inTransaction(() -> service.archiveUnderperforming("tenant-a", 9L));

        assertEquals(1, count);
        assertEquals(AgentMemoryEntryEntity.Status.ACTIVE, reload(sameOwnerWrongTenant).getStatus());
        assertEquals(AgentMemoryEntryEntity.Status.ACTIVE, reload(wrongOwner).getStatus());
        assertEquals(AgentMemoryEntryEntity.Status.ARCHIVED, reload(rightOwner).getStatus());
    }

    @Test
    void staleVersionOrChangedStateCannotBeArchived() {
        Long id = persist("stale", 20, 0.0, 4, AgentMemoryEntryEntity.Status.ACTIVE);

        int staleVersion = inTransaction(() -> procedures.archiveIfStillUnderperforming(id, "tenant-a",
                AgentMemoryEntryEntity.ScopeType.USER, "9", 3, 10L, 0.20,
                AgentMemoryEntryEntity.Status.ACTIVE, AgentMemoryEntryEntity.Status.ARCHIVED));
        int alreadyChangedState = inTransaction(() -> procedures.archiveIfStillUnderperforming(id, "tenant-a",
                AgentMemoryEntryEntity.ScopeType.USER, "9", 4, 10L, 0.20,
                AgentMemoryEntryEntity.Status.ARCHIVED, AgentMemoryEntryEntity.Status.ARCHIVED));

        assertEquals(0, staleVersion, "版本变化后的判定必须失效");
        assertEquals(0, alreadyChangedState, "状态不再 ACTIVE 时必须失效");
        assertEquals(AgentMemoryEntryEntity.Status.ACTIVE, reload(id).getStatus());
    }

    @Test
    void missingOwnerDoesNotTouchAnyProcedure() {
        persist("tenant-a", "9", "candidate", 20, 0.0, 1, AgentMemoryEntryEntity.Status.ACTIVE);
        ProcedureGovernanceService service = new ProcedureGovernanceService(procedures,
                properties(10, 0.20));

        assertEquals(0, service.archiveUnderperforming("tenant-a", null));
        assertEquals(1, inTransaction(() -> procedures.findByTenantIdAndScopeTypeAndScopeIdAndStatus(
                "tenant-a", AgentMemoryEntryEntity.ScopeType.USER, "9",
                AgentMemoryEntryEntity.Status.ACTIVE)).size());
    }

    private ProcedureGovernanceProperties properties(long minAttempts, double maxRate) {
        ProcedureGovernanceProperties properties = new ProcedureGovernanceProperties();
        properties.setMinAttempts(minAttempts);
        properties.setArchiveMaxSuccessRate(maxRate);
        return properties;
    }

    private Long persist(String name, long attempts, double successRate, int version,
                         AgentMemoryEntryEntity.Status status) {
        return persist("tenant-a", "9", name, attempts, successRate, version, status);
    }

    private Long persist(String tenant, String owner, String name, long attempts,
                         double successRate, int version, AgentMemoryEntryEntity.Status status) {
        AgentProcedureEntity entity = new AgentProcedureEntity();
        entity.setTenantId(tenant);
        entity.setScopeType(AgentMemoryEntryEntity.ScopeType.USER);
        entity.setScopeId(owner);
        entity.setName(name);
        entity.setStepsJson("[]");
        entity.setAttemptCount(attempts);
        entity.setSuccessCount(Math.round(attempts * successRate));
        entity.setSuccessRate(successRate);
        entity.setProcedureVersion(version);
        entity.setStatus(status);
        return inTransaction(() -> procedures.saveAndFlush(entity).getId());
    }

    private AgentProcedureEntity reload(Long id) {
        return inTransaction(() -> procedures.findById(id).orElseThrow());
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
}
