package com.miniagent.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.permission.AskGrant;
import com.miniagent.agent.permission.SessionPermissionPersistence;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.config.entity.AgentSessionPermission;
import com.miniagent.config.repository.AgentSessionPermissionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@Import({
        DbSessionPermissionPersistence.class,
        JacksonAutoConfiguration.class
})
class DbSessionPermissionAlwaysAllowPersistenceTest {

    @Autowired
    private DbSessionPermissionPersistence persistence;
    @Autowired
    private AgentSessionPermissionRepository repository;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void legacyArrayJsonDeserializesWithoutAlways() {
        seed("legacy-array", "[\"a\",\"b\"]");

        SessionPermissionPersistence.State state =
                persistence.loadOrCreate("legacy-array");
        assertEquals(Set.of("a", "b"), state.askGrantedTools());
        assertTrue(state.askGrants().isEmpty());
        assertTrue(state.alwaysAllowedTools().isEmpty(),
                "旧数组格式里没有 always 数据，不能凭空造出授权");
    }

    @Test
    void legacyFlatObjectJsonDeserializesWithoutAlways() throws Exception {
        AskGrant grant = new AskGrant("tok-1", "exec_command", "digest-1",
                System.currentTimeMillis() + 60_000L);
        String legacyObject = objectMapper.writeValueAsString(
                Map.of(grant.token(), grant));
        seed("legacy-object", legacyObject);

        SessionPermissionPersistence.State state =
                persistence.loadOrCreate("legacy-object");
        assertEquals(grant, state.askGrants().get("tok-1"));
        assertEquals(Set.of("exec_command"), state.askGrantedTools());
        assertTrue(state.alwaysAllowedTools().isEmpty());
    }

    @Test
    void alwaysAllowedToolsRoundTripThroughWrapperAndCasVersionIncrements() {
        SessionPermissionPersistence.State initial =
                persistence.loadOrCreate("wrapper");
        assertEquals(0L, initial.version());

        SessionPermissionPersistence.State next =
                new SessionPermissionPersistence.State(
                        "default", false, Set.of(), Map.of(), "dangerous", null,
                        Set.of("exec_command", "read_file"),
                        initial.version() + 1L);

        assertTrue(persistence.compareAndSet("wrapper", initial, next));
        assertFalse(persistence.compareAndSet("wrapper", initial, next),
                "同一 expected 版本不能重复 CAS 成功");

        SessionPermissionPersistence.State restored =
                persistence.loadOrCreate("wrapper");
        assertEquals(1L, restored.version());
        assertEquals(Set.of("exec_command", "read_file"),
                restored.alwaysAllowedTools());

        String storedJson = repository.findById("wrapper").orElseThrow()
                .getAskGrantsJson();
        assertTrue(storedJson.contains("alwaysAllowedTools"),
                "新格式必须带 alwaysAllowedTools 形状标记");
    }

    @Test
    void storeAlwaysAllowGoesThroughCasAndRevokePersistsEmpty() {
        SessionPermissionStore store = new SessionPermissionStore(persistence);
        assertEquals(0L, persistence.loadOrCreate("chain").version());

        store.alwaysAllow("chain", "toolA");
        assertEquals(1L, persistence.loadOrCreate("chain").version(),
                "alwaysAllow 必须走 CAS，版本号 +1");
        assertEquals(Set.of("toolA"),
                persistence.loadOrCreate("chain").alwaysAllowedTools());

        // 重启一个 Store 读同一份持久化：always 仍在，且命中后不再要求 argumentDigest。
        SessionPermissionStore restarted = new SessionPermissionStore(persistence);
        assertTrue(restarted.isAlwaysAllowed("chain", "toolA"));
        assertTrue(restarted.isAskGranted("chain", "toolA", "任意参数"));

        restarted.revokeAlwaysAllow("chain", "toolA");
        assertEquals(2L, persistence.loadOrCreate("chain").version(),
                "撤销同样走 CAS");
        assertTrue(persistence.loadOrCreate("chain").alwaysAllowedTools().isEmpty());
        assertFalse(new SessionPermissionStore(persistence)
                        .isAlwaysAllowed("chain", "toolA"),
                "撤销后恢复询问");
    }

    /** 直接预置一行带旧格式 JSON 的记录，模拟存量库数据。 */
    private void seed(String sessionId, String askGrantsJson) {
        AgentSessionPermission entity = new AgentSessionPermission();
        entity.setSessionId(sessionId);
        entity.setMode("default");
        entity.setPlanApproved(false);
        entity.setAskGrantsJson(askGrantsJson);
        entity.setConfirmPolicy("dangerous");
        repository.saveAndFlush(entity);
    }
}
