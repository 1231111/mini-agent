package com.miniagent.config.service;

import com.miniagent.agent.permission.SessionPermissionPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

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
class DbSessionPermissionPersistenceTest {

    @Autowired
    private DbSessionPermissionPersistence persistence;

    @Test
    void persistsEveryPermissionFieldWithVersionCas() {
        SessionPermissionPersistence.State initial =
                persistence.loadOrCreate("s1");
        SessionPermissionPersistence.State next =
                new SessionPermissionPersistence.State(
                        "plan",
                        true,
                        Set.of("write_file"),
                        "auto",
                        "block",
                        initial.version() + 1L);

        assertTrue(persistence.compareAndSet("s1", initial, next));
        assertFalse(persistence.compareAndSet("s1", initial, next));

        SessionPermissionPersistence.State restored =
                persistence.loadOrCreate("s1");
        assertEquals("plan", restored.mode());
        assertTrue(restored.planApproved());
        assertEquals(Set.of("write_file"), restored.askGrantedTools());
        assertEquals("auto", restored.confirmPolicy());
        assertEquals("block", restored.execPolicyOverride());
        assertEquals(next.version(), restored.version());
    }
}
