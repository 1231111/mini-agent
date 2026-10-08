package com.miniagent.config.service;

import com.miniagent.agent.scope.TaskScopePersistence;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "agent.todo.storage=db"
})
@Import(DbSessionTodoPersistence.class)
class DbTaskScopePersistenceTest {

    @Autowired
    private DbSessionTodoPersistence persistence;

    @Test
    void scopeTransitionUsesVersionCasAndSurvivesTodoWrites() {
        TaskScopePersistence.State initial =
                persistence.loadOrCreate("s1");
        TaskScopePersistence.State taskOne = new TaskScopePersistence.State(
                1L, 0L, 1L, initial.version() + 1L);

        assertTrue(persistence.compareAndSet("s1", initial, taskOne));
        assertFalse(persistence.compareAndSet("s1", initial, taskOne));

        persistence.save("s1", "[{\"content\":\"work\"}]", null);
        TaskScopePersistence.State restored =
                persistence.loadOrCreate("s1");

        assertEquals(1L, restored.currentTaskId());
        assertEquals(0L, restored.pausedTaskId());
        assertEquals(1L, restored.sequence());
        assertTrue(restored.version() > taskOne.version());

        persistence.delete("s1");
        TaskScopePersistence.State afterTodoClear =
                persistence.loadOrCreate("s1");
        assertEquals(1L, afterTodoClear.currentTaskId());
        assertEquals(1L, afterTodoClear.sequence());
    }
}
