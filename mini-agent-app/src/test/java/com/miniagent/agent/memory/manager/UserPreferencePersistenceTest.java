package com.miniagent.agent.memory.manager;

import com.miniagent.agent.memory.entity.AgentMemoryEntryEntity;
import com.miniagent.agent.memory.repository.AgentSemanticFactRepository;
import com.miniagent.common.context.OwnerContext;
import com.miniagent.memory.DefaultMemoryService;
import com.miniagent.memory.MemoryKeys;
import com.miniagent.memory.MemoryStore;
import com.miniagent.memory.model.MemoryScope;
import com.miniagent.memory.model.SemanticFact;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 验证真实仓储落库、重载、替代和软删除，避免 mock 掩盖归属域与生命周期映射缺陷。 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:user-preferences;MODE=MySQL;DB_CLOSE_DELAY=-1"
})
class UserPreferencePersistenceTest {

    @Autowired
    private AgentSemanticFactRepository facts;

    @Autowired
    private EntityManager entityManager;

    @TempDir
    Path tempDir;

    private DefaultMemoryManager manager;
    private DefaultMemoryService service;

    @BeforeEach
    void setUp() {
        OwnerContext.setCurrentTenant("tenant-a");
        OwnerContext.setCurrentUser(7L);
        manager = new DefaultMemoryManager();
        ReflectionTestUtils.setField(manager, "factRepository", facts);
        MemoryStore store = new MemoryStore(tempDir);
        store.loadFromDisk();
        service = new DefaultMemoryService();
        ReflectionTestUtils.setField(service, "memoryStore", store);
        ReflectionTestUtils.setField(service, "memoryManager", manager);
    }

    @AfterEach
    void clearOwner() {
        OwnerContext.clearCurrentUser();
        OwnerContext.clearCurrentTenant();
    }

    @Test
    void preferenceCrudRemainsCorrectAfterDatabaseReload() {
        Map<String, Object> added = service.add("user", "人物介绍使用精简列表");
        assertEquals(true, added.get("persisted"));
        assertNotNull(added.get("fact_id"));
        reload();
        assertEquals(List.of("人物介绍使用精简列表"), service.read("user"));

        Map<String, Object> replaced = service.replace("user", "精简列表", "人物介绍使用图文卡片");
        assertEquals(true, replaced.get("persisted"));
        assertNotEquals(added.get("fact_id"), replaced.get("fact_id"));
        reload();
        assertEquals(List.of("人物介绍使用图文卡片"), service.read("user"));
        List<SemanticFact> versions = manager.queryFacts("tenant-a", "USER", "7", null);
        assertEquals(2, versions.size());
        SemanticFact old = versions.stream().filter(f -> f.getId().equals(added.get("fact_id")))
                .findFirst().orElseThrow();
        assertFalse(old.isValid());
        assertNotNull(old.getValidTo());
        assertEquals(replaced.get("fact_id"), old.getSupersededBy());

        assertEquals(true, service.remove("user", "图文卡片").get("persisted"));
        reload();
        assertTrue(service.read("user").isEmpty());
        assertTrue(manager.queryFacts("tenant-a", "USER", "7", null).stream()
                .noneMatch(SemanticFact::isValid));
    }

    @Test
    void supersedingAnIdenticalTripleMustStayWithinTenantAndScope() {
        manager.writeFact(fact("tenant-a", MemoryScope.ofUser("tenant-a", "7"), "旧偏好"));
        manager.writeFact(fact("tenant-a", MemoryScope.ofUser("tenant-a", "8"), "另一用户偏好"));
        manager.writeFact(fact("tenant-a", MemoryScope.ofProject("tenant-a", "7"), "项目偏好"));
        manager.writeFact(fact("tenant-b", MemoryScope.ofUser("tenant-b", "7"), "另一租户偏好"));
        manager.writeFact(fact("tenant-a", MemoryScope.ofUser("tenant-a", "7"), "新偏好"));
        reload();

        List<SemanticFact> own = manager.queryFacts("tenant-a", "USER", "7", null);
        assertEquals(2, own.size());
        assertEquals(List.of("新偏好"), own.stream().filter(SemanticFact::isValid)
                .map(SemanticFact::getObjectValue).toList());
        assertTrue(manager.queryFacts("tenant-a", "USER", "8", null).getFirst().isValid());
        assertTrue(manager.queryFacts("tenant-a", "PROJECT", "7", null).getFirst().isValid());
        assertTrue(manager.queryFacts("tenant-b", "USER", "7", null).getFirst().isValid());
    }

    @Test
    void removeRequiresMatchingTenantScopeTypeAndOwnerAndPreservesLifecycleFields() {
        manager.writeFact(fact("tenant-a", MemoryScope.ofUser("tenant-a", "7"), "图文偏好"));
        reload();
        SemanticFact saved = manager.queryFacts("tenant-a", "USER", "7", null).getFirst();
        assertTrue(saved.getCreatedAt() > 0);
        assertTrue(saved.getUpdatedAt() > 0);
        assertNotNull(saved.getValidFrom());
        assertFalse(manager.removeFact("tenant-b", "USER", "7", saved.getId()));
        assertFalse(manager.removeFact("tenant-a", "USER", "8", saved.getId()));
        assertFalse(manager.removeFact("tenant-a", "PROJECT", "7", saved.getId()));
        assertTrue(manager.queryFacts("tenant-a", "USER", "7", null).getFirst().isValid());

        assertTrue(manager.removeFact("tenant-a", "USER", "7", saved.getId()));
        reload();
        SemanticFact removed = manager.queryFacts("tenant-a", "USER", "7", null).getFirst();
        assertFalse(removed.isValid());
        assertNotNull(removed.getValidTo());
        assertFalse(manager.removeFact("tenant-a", "USER", "7", saved.getId()));
        assertEquals(1, facts.findByTenantIdAndScopeTypeAndScopeId("tenant-a",
                AgentMemoryEntryEntity.ScopeType.USER, "7").size(), "软删除保留审计记录");
    }

    private void reload() {
        entityManager.flush();
        entityManager.clear();
    }

    private static SemanticFact fact(String tenant, MemoryScope scope, String content) {
        SemanticFact fact = new SemanticFact();
        fact.setTenantId(tenant);
        fact.setScope(scope);
        fact.setSubject("人物介绍格式");
        fact.setPredicate(MemoryKeys.PREDICATE_PREFERENCE);
        fact.setObjectValue(content);
        fact.setSource(MemoryKeys.SOURCE_MEMORY_TOOL);
        return fact;
    }
}
