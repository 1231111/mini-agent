package com.miniagent.agent.memory.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.entity.AgentProcedureEntity;
import com.miniagent.agent.memory.repository.AgentProcedureRepository;
import com.miniagent.memory.model.MemoryScope;
import com.miniagent.memory.model.Procedure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在真实数据库（H2，MySQL 兼容模式）上验证 SOP 的身份与升版语义。
 *
 * <p>要防的错是这次改掉的那个：写入路径无条件 insert、版本恒为 1，同名 SOP 写两次就是两行
 * {@code (name, version=1)}。两行都进规划候选，结算计数只落其中一行，闭环拿不到反馈。
 * 这些断言都要求「库里真的只有一行」，mock 看不见。</p>
 *
 * <p>刻意关掉测试事务（{@code NOT_SUPPORTED}）：{@code insertIfAbsent} 走
 * {@code REQUIRES_NEW}，它提交后不会随测试回滚，留着测试事务会让状态跨方法累积，
 * 用例顺序一变就莫名其妙地红。改为每个用例前显式清表。</p>
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:procedure-upsert;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProcedureUpsertPersistenceTest {

    private static final String TENANT = "tenant-a";
    private static final String OWNER = "9";

    @Autowired
    private AgentProcedureRepository procedures;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private com.miniagent.agent.memory.repository.AgentProcedureRevisionRepository revisions;

    private ProcedureUpsertService service;

    @BeforeEach
    void setUp() {
        revisions.deleteAll();
        procedures.deleteAll();
        service = new ProcedureUpsertService(procedures, new ObjectMapper(), transactionManager,
                new ProcedureRevisionService(procedures, revisions, new ObjectMapper()));
    }

    @Test
    void sameNameTwiceKeepsOneRowAndBumpsVersionInPlace() {
        ProcedureUpsertService.Upserted first = upsert(procedure("发布检查清单", "跑测试", "打 tag"));
        Long id = first.entity().getId();
        ProcedureUpsertService.Upserted second =
                upsert(procedure("发布检查清单", "跑测试", "打 tag", "发公告"));

        assertTrue(first.created(), "首次写入应当是新建");
        assertFalse(second.created(), "同名写入不是新建，是升版");
        assertEquals(id, second.entity().getId(),
                "升版必须原地复用同一行：id 变了，已经指向这条 SOP 的节点引用就断了");
        assertEquals(1, first.entity().getProcedureVersion().intValue(), "起点是 v1");
        assertEquals(2, reload(id).getProcedureVersion().intValue());
        assertEquals(1, rows().size(),
                "同名 SOP 在同一个归属域里只能有一行；出现两行就是回到了修复前那个状态");
    }

    @Test
    void identicalContentIsIdempotent() {
        upsert(procedure("发布检查清单", "跑测试"));
        ProcedureUpsertService.Upserted again = upsert(procedure("发布检查清单", "跑测试"));

        assertFalse(again.created());
        assertEquals(1, again.entity().getProcedureVersion().intValue(),
                "内容没变就不该升版：重复保存同一份 SOP 不应产出一个空有版本号、没有证据的新版本");
        assertEquals(1, rows().size());
    }

    @Test
    void nameCaseAndPaddingDoNotCreateASecondIdentity() {
        ProcedureUpsertService.Upserted first = upsert(procedure("Deploy Checklist", "build"));
        ProcedureUpsertService.Upserted second =
                upsert(procedure("  deploy checklist  ", "build"));

        assertEquals(first.entity().getId(), second.entity().getId(),
                "身份按 trim + lowercase 归一，大小写/空白差异不该造出第二行");
        assertEquals(1, rows().size());
        assertEquals("deploy checklist", reload(first.entity().getId()).getName(),
                "落库名字只去首尾空白，保留调用方这次写的大小写");
    }

    @Test
    void importanceChangeDoesNotResetTheVersionEvidence() {
        Long id = upsert(procedure("发布检查清单", "跑测试")).entity().getId();
        settle(id, 1, 1);

        Procedure changedImportance = procedure("发布检查清单", "跑测试");
        changedImportance.setImportance(0.95);
        ProcedureUpsertService.Upserted second = upsert(changedImportance);

        assertEquals(1, second.entity().getProcedureVersion().intValue(),
                "重要度是排序权重，不是 SOP 内容；调权重不该升版");
        AgentProcedureEntity stored = reload(id);
        assertEquals(0.95, stored.getImportance().doubleValue(), 1e-9);
        assertEquals(1L, stored.getAttemptCount().longValue(), "调权重不该清掉这一版已经积累的结算证据");
    }

    @Test
    void upgradeResetsPerVersionCountersAndStaleSettlementCannotPolluteThem() {
        Long id = upsert(procedure("发布检查清单", "跑测试")).entity().getId();
        settle(id, 1, 1);
        settle(id, 1, 0);
        assertEquals(0.5, reload(id).getSuccessRate().doubleValue(), 1e-9, "两个节点一成一败");

        upsert(procedure("发布检查清单", "跑测试", "打 tag"));

        AgentProcedureEntity upgraded = reload(id);
        assertEquals(2, upgraded.getProcedureVersion().intValue());
        assertEquals(0L, upgraded.getAttemptCount().longValue(), "v2 的计数必须从零开始：v1 的成绩不属于这一版内容");
        assertEquals(0L, upgraded.getSuccessCount().longValue());
        assertEquals(0.0, upgraded.getSuccessRate().doubleValue(), 1e-9);
        assertEquals(0, upgraded.getUsageCount().intValue());

        int stale = settleQuietly(id, 1);
        assertEquals(0, stale,
                "还持有 v1 版本号的在跑节点结算不到 v2 的计数，只能记事实、跳过统计");
        assertEquals(0L, reload(id).getAttemptCount().longValue());
    }

    @Test
    void archivedRowWithSameNameIsRevivedInsteadOfDuplicated() {
        Long id = persist("发布检查清单", AgentMemoryEntryEntity.Status.ARCHIVED);

        ProcedureUpsertService.Upserted upserted = upsert(procedure("发布检查清单", "跑测试"));

        assertEquals(id, upserted.entity().getId(),
                "归档后重新添加同名 SOP 必须落回原来那一行：唯一身份不允许同名两行");
        AgentProcedureEntity revived = reload(id);
        assertEquals(AgentMemoryEntryEntity.Status.ACTIVE, revived.getStatus());
        assertEquals(2, revived.getProcedureVersion().intValue(),
                "复活视为新的一版（内容已改），不是接着 v1 的旧计数继续算");
    }

    @Test
    void revisionSnapshotKeepsJsonArraysAndRollbackRestoresTheirStructure() throws Exception {
        Long id = upsert(procedure("发布检查清单", "跑测试", "部署服务")).entity().getId();
        AgentProcedureEntity initial = reload(id);
        ProcedureRevisionService revisionService = new ProcedureRevisionService(
                procedures, revisions, new ObjectMapper());
        revisionService.capture(initial, "TEST", null, null);

        var snapshot = new ObjectMapper().readTree(revisions
                .findByTenantIdAndProcedureIdAndProcedureVersion(TENANT, id, 1)
                .orElseThrow().getContentJson());
        assertTrue(snapshot.get("steps").isArray());
        assertEquals("跑测试", snapshot.get("steps").get(0).asText());

        upsert(procedure("发布检查清单", "跑测试", "部署服务", "验证告警"));
        try (var ignored = com.miniagent.common.context.OwnerContext.bind(
                new com.miniagent.common.context.OwnerContext(9L, TENANT))) {
            assertTrue(inTransaction(() -> revisionService.rollback(id, 1)));
        }

        AgentProcedureEntity restored = reload(id);
        var restoredSteps = new ObjectMapper().readTree(restored.getStepsJson());
        assertTrue(restoredSteps.isArray());
        assertEquals(2, restoredSteps.size());
        assertEquals("部署服务", restoredSteps.get(1).asText());
    }

    @Test
    void uniqueIndexIsTheLastLineOfDefenceAgainstDuplicateIdentities() {
        persist("发布检查清单", AgentMemoryEntryEntity.Status.ACTIVE);

        AgentProcedureEntity duplicate = baseEntity("发布检查清单");
        assertThrows(DataIntegrityViolationException.class,
                () -> inTransaction(() -> procedures.saveAndFlush(duplicate)),
                "同名两行必须在库层面被拒绝，而不是靠调用方自觉");
    }

    /**
     * 校验的是「写入入口」到库的整条路径，而不是 service 自己。
     *
     * <p>只测 {@code ProcedureUpsertService} 会漏掉一种回退：service 写得再对，
     * {@code DefaultMemoryManager.writeProcedure} 只要有人改回「new 一个实体然后 save」，
     * 同名 SOP 又会长出第二行 —— 而 service 的用例全绿。所以这里必须从入口打进去。</p>
     */
    @Test
    void writeProcedureEntryPointKeepsIdentityAndBumpsVersion() {
        DefaultMemoryManager manager = new DefaultMemoryManager();
        ReflectionTestUtils.setField(manager, "procedureUpsertService", service);
        // 仓储也注入：否则「有人改回直接 save」时用例只会因 NPE 变红，
        // 看不出真正的问题（库里真的多了一行）。
        ReflectionTestUtils.setField(manager, "procedureRepository", procedures);

        Procedure v1 = inTransaction(() ->
                manager.writeProcedure(procedure("发布检查清单", "跑测试")));
        Procedure v2 = inTransaction(() ->
                manager.writeProcedure(procedure("发布检查清单", "跑测试", "打 tag")));

        assertEquals(v1.getId(), v2.getId(), "写入入口必须落到同一行");
        assertEquals(1, v1.getVersion().intValue());
        assertEquals(2, v2.getVersion().intValue(),
                "写接口要交回落库后的真实版本，否则调用方无法知道升到第几版了");
        assertEquals(1, rows().size());
    }

    @Test
    void staleExpectedVersionCannotOverwriteNewerContent() {
        Long id = persist("发布检查清单", AgentMemoryEntryEntity.Status.ACTIVE);

        int updated = inTransaction(() -> procedures.upgradeContent(id, TENANT, 99, 100,
                "发布检查清单", null, "[]", "[]", "[]", 0.5,
                AgentMemoryEntryEntity.Status.ACTIVE));

        assertEquals(0, updated, "CAS 靠 procedure_version 把关，版本对不上就不许写");
    }

    // --- helpers ---

    private ProcedureUpsertService.Upserted upsert(Procedure procedure) {
        // 生产里 upsert 跑在 writeProcedure 的事务里（批量 UPDATE 需要事务），这里显式补上外层事务
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> service.upsert(procedure));
    }

    private int settleQuietly(Long id, int version) {
        return inTransaction(() -> procedures.bumpSettlement(id, TENANT,
                AgentMemoryEntryEntity.ScopeType.USER, OWNER, version, 1));
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private void settle(Long id, int version, int successDelta) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            procedures.bumpSettlement(id, TENANT, AgentMemoryEntryEntity.ScopeType.USER,
                    OWNER, version, successDelta);
            procedures.refreshSuccessRate(id, TENANT);
        });
    }

    private Procedure procedure(String name, String... steps) {
        Procedure p = new Procedure();
        p.setTenantId(TENANT);
        p.setScope(MemoryScope.ofUser(TENANT, OWNER));
        p.setName(name);
        p.setDescription("同一条 SOP");
        p.setSteps(List.of(steps));
        p.setPreconditions(List.of());
        p.setSuccessConditions(List.of());
        return p;
    }

    private AgentProcedureEntity reload(Long id) {
        return procedures.findById(id).orElseThrow();
    }

    private List<AgentProcedureEntity> rows() {
        return procedures.findByTenantIdAndScopeTypeAndScopeId(
                TENANT, AgentMemoryEntryEntity.ScopeType.USER, OWNER);
    }

    private Long persist(String name, AgentMemoryEntryEntity.Status status) {
        AgentProcedureEntity entity = baseEntity(name);
        entity.setStatus(status);
        entity.setProcedureVersion(1);
        return inTransaction(() -> procedures.saveAndFlush(entity).getId());
    }

    private AgentProcedureEntity baseEntity(String name) {
        AgentProcedureEntity entity = new AgentProcedureEntity();
        entity.setTenantId(TENANT);
        entity.setScopeType(AgentMemoryEntryEntity.ScopeType.USER);
        entity.setScopeId(OWNER);
        entity.setName(name);
        entity.setStepsJson("[]");
        return entity;
    }
}
