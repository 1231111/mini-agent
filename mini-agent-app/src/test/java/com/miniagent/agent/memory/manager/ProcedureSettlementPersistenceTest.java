package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentProcedureApplicationEntity;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import com.miniagent.agent.memory.repository.AgentProcedureApplicationRepository;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 在真实数据库（H2，MySQL 兼容模式）上验证结算的计数语义。
 *
 * <p>既有的 {@code ProcedureApplicationSettlementServiceTest} 用 mock 校验调用形状，
 * 但恰好看不见这里要防的错：成功率是由计数**重算**出来的，若把比率写在同一条
 * UPDATE 里、又依赖「右侧读到旧值」，MySQL 会把成功数双倍计入（第一个成功就得到 2.0）。
 * mock 永远返回固定值，所以只有真库能暴露。</p>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:procedure-settlement;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
class ProcedureSettlementPersistenceTest {

    @Autowired
    private AgentProcedureRepository procedures;

    @Autowired
    private AgentProcedureApplicationRepository applications;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void successRateIsRecomputedFromCountersAndNoDoubleCounting() {
        Long id = persistProcedure();

        bump(id, 1);
        assertEquals(1.0, reload(id).getSuccessRate(), 1e-9,
                "第一次成功必须是 1/1，旧实现同句求值会得到 2/1=2.0");

        bump(id, 0);
        AgentProcedureEntity second = reload(id);
        assertEquals(2L, second.getAttemptCount().longValue());
        assertEquals(1L, second.getSuccessCount().longValue());
        assertEquals(2, second.getUsageCount().intValue());
        assertEquals(0.5, second.getSuccessRate(), 1e-9);
    }

    @Test
    void settlementRequiresMatchingTenantOwnerAndVersion() {
        Long id = persistProcedure();

        int wrongOwner = procedures.bumpSettlement(id, "tenant-a",
                AgentMemoryEntryEntity.ScopeType.USER, "999", 1, 1);
        int wrongTenant = procedures.bumpSettlement(id, "tenant-b",
                AgentMemoryEntryEntity.ScopeType.USER, "9", 1, 1);
        int staleVersion = procedures.bumpSettlement(id, "tenant-a",
                AgentMemoryEntryEntity.ScopeType.USER, "9", 2, 1);
        int rightOwner = procedures.bumpSettlement(id, "tenant-a",
                AgentMemoryEntryEntity.ScopeType.USER, "9", 1, 1);

        assertEquals(0, wrongOwner);
        assertEquals(0, wrongTenant);
        assertEquals(0, staleVersion);
        assertEquals(1, rightOwner);
    }

    /**
     * 唯一键是幂等的最终裁判。这里直接撞库验证它真的存在且生效：
     * 同节点、同版本 Procedure 的第二次尝试必须被拒绝，
     * 而不是悄悄多出一条把它算成两次使用。
     */
    @Test
    void uniqueKeyRejectsDuplicateApplicationAttempt() {
        applications.saveAndFlush(application("action-1"));
        entityManager.flush();

        assertThrows(DataIntegrityViolationException.class,
                () -> applications.saveAndFlush(application("action-2")));
    }

    private AgentProcedureApplicationEntity application(String actionId) {
        AgentProcedureApplicationEntity entity = new AgentProcedureApplicationEntity();
        entity.setTenantId("tenant-a");
        entity.setUserId(9L);
        entity.setSessionId("session");
        entity.setTaskId(4L);
        entity.setNodeId("node");
        entity.setProcedureId(22L);
        entity.setProcedureVersion(1);
        entity.setActionId(actionId);
        entity.setResult(AgentProcedureApplicationEntity.Result.SUCCESS);
        return entity;
    }

    private Long persistProcedure() {
        AgentProcedureEntity entity = new AgentProcedureEntity();
        entity.setTenantId("tenant-a");
        entity.setScopeType(AgentMemoryEntryEntity.ScopeType.USER);
        entity.setScopeId("9");
        entity.setName("release checklist");
        entity.setStepsJson("[]");
        AgentProcedureEntity saved = procedures.saveAndFlush(entity);
        entityManager.clear();
        return saved.getId();
    }

    private void bump(Long id, int successDelta) {
        procedures.bumpSettlement(id, "tenant-a", AgentMemoryEntryEntity.ScopeType.USER,
                "9", 1, successDelta);
        procedures.refreshSuccessRate(id, "tenant-a");
        entityManager.clear();
    }

    private AgentProcedureEntity reload(Long id) {
        return procedures.findById(id).orElseThrow();
    }
}
