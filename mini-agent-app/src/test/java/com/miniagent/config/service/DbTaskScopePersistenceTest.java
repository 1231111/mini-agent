package com.miniagent.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper JSON = new ObjectMapper();

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

    @Test
    void taskPartitionWritesMergeAgainstLatestSharedRow() throws Exception {
        persistence.save("s2", "{\"0\":[{\"id\":1,\"content\":\"legacy\"}]}", null);

        // Simulate two replicas that both loaded the same old session snapshot. Each
        // submits its own partition plus a stale full-row compatibility snapshot.
        persistence.saveTaskPartition("s2", 1L,
                "[{\"id\":1,\"content\":\"task-one\"}]",
                "{\"0\":[{\"id\":1,\"content\":\"legacy\"}],"
                        + "\"1\":[{\"id\":1,\"content\":\"task-one\"}]}" );
        persistence.saveTaskPartition("s2", 2L,
                "[{\"id\":1,\"content\":\"task-two\"}]",
                "{\"0\":[{\"id\":1,\"content\":\"legacy\"}],"
                        + "\"2\":[{\"id\":1,\"content\":\"task-two\"}]}" );

        JsonNode saved = JSON.readTree(persistence.load("s2").activeJson());
        assertEquals("legacy", saved.path("0").path(0).path("content").asText());
        assertEquals("task-one", saved.path("1").path(0).path("content").asText());
        assertEquals("task-two", saved.path("2").path(0).path("content").asText());
    }

    @Test
    void partitionRemovalAndArchiveKeepOtherTaskState() throws Exception {
        persistence.save("s3", "{\"0\":[{\"id\":1,\"content\":\"keep\"}],"
                + "\"1\":[{\"id\":1,\"content\":\"remove\"}]}", null);

        persistence.saveTaskPartition("s3", 1L, null, "{}");
        persistence.saveTaskPartition("s3", 0L,
                "{\"archived\":true,\"items\":[{\"id\":1,\"content\":\"keep\"}]}", "{}");

        JsonNode saved = JSON.readTree(persistence.load("s3").activeJson());
        assertFalse(saved.has("1"), "an explicitly removed partition must stay removed");
        assertTrue(saved.path("0").path("archived").asBoolean());
        assertEquals("keep", saved.path("0").path("items").path(0)
                .path("content").asText());
    }
}
