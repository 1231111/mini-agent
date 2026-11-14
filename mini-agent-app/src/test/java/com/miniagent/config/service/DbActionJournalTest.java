package com.miniagent.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.execution.ActionExecutionStatus;
import com.miniagent.agent.execution.ActionJournal;
import com.miniagent.agent.execution.ActionJournalEntry;
import com.miniagent.agent.execution.ActionJournalKey;
import com.miniagent.common.io.AgentDataPaths;
import com.miniagent.migration.V28__DurableActionClaims;
import org.flywaydb.core.api.migration.Context;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DbActionJournalTest {
    private static final ActionJournalKey KEY = new ActionJournalKey("fixture-session", "fixture-execution:1", "send", "fixture-key");
    private static final String HASH = "a".repeat(64);
    @TempDir Path directory;

    @Test void twoReplicasReadingAbsentKeyCanAcquireExactlyOneDurablePermit() throws Exception {
        JdbcDataSource database = database();
        DataSource racing = synchronizedReads(database);
        DbActionJournal first = journal(racing), second = journal(racing);
        List<ActionJournal.Claim> claims = race(() -> first.claim(KEY, "send", HASH, false),
                () -> second.claim(KEY, "send", HASH, false));
        assertEquals(1, claims.stream().filter(ActionJournal.Claim::acquired).count());
        assertTrue(claims.stream().allMatch(claim -> claim.entry().status() == ActionExecutionStatus.RUNNING));
        assertEquals(1, new JdbcTemplate(database).queryForObject("SELECT COUNT(*) FROM agent_action_receipt", Integer.class));
        assertFalse(journal(database).claim(KEY, "send", HASH, false).acquired(), "fresh replica must see the committed ownership");
    }

    @Test void twoReplicasRetryingTheSameFailedAttemptCanAcquireOnlyOneNextAttempt() throws Exception {
        JdbcDataSource database = database();
        DbActionJournal setup = journal(database);
        ActionJournal.Claim original = setup.claim(KEY, "read", HASH, true);
        setup.append(terminal(original, ActionExecutionStatus.FAILED));
        DataSource racing = synchronizedReads(database);
        DbActionJournal first = journal(racing), second = journal(racing);
        List<ActionJournal.Claim> claims = race(() -> first.claim(KEY, "read", HASH, true),
                () -> second.claim(KEY, "read", HASH, true));
        assertEquals(1, claims.stream().filter(ActionJournal.Claim::acquired).count());
        assertTrue(claims.stream().allMatch(claim -> claim.entry().attempt() == 2));
        assertEquals(ActionExecutionStatus.RUNNING, setup.latest(KEY).orElseThrow().status());
    }

    @Test void effectClaimsAcrossReplicasArbitrateAnAbsentKeyAtomically() throws Exception {
        JdbcDataSource database = database();
        DataSource racing = synchronizedReads(database);
        DbActionJournal first = journal(racing), second = journal(racing);
        List<ActionJournal.Claim> claims = race(() -> first.claimEffect(KEY, "click", HASH),
                () -> second.claimEffect(KEY, "click", HASH));
        assertEquals(1, claims.stream().filter(ActionJournal.Claim::acquired).count());
        assertFalse(journal(database).claimEffect(KEY, "click", HASH).acquired());
    }

    @Test void aConfirmedEffectCanRepeatButConcurrentReplicasCannotOwnTheSameNextEffect() throws Exception {
        JdbcDataSource database = database();
        DbActionJournal setup = journal(database);
        ActionJournal.Claim original = setup.claimEffect(KEY, "click", HASH);
        setup.append(terminal(original, ActionExecutionStatus.SUCCEEDED));
        assertFalse(setup.claim(KEY, "click", HASH, true).acquired(), "operation success remains immutable");
        DataSource racing = synchronizedReads(database);
        DbActionJournal first = journal(racing), second = journal(racing);
        List<ActionJournal.Claim> claims = race(() -> first.claimEffect(KEY, "click", HASH),
                () -> second.claimEffect(KEY, "click", HASH));
        assertEquals(1, claims.stream().filter(ActionJournal.Claim::acquired).count());
        assertTrue(claims.stream().allMatch(claim -> claim.entry().attempt() == 2));
        assertThrows(IllegalStateException.class, () -> setup.append(terminal(original, ActionExecutionStatus.SUCCEEDED)));
    }

    @Test void effectClaimsNeverTakeOverRunningOrUnknownEvenAfterReplicaRestart() throws Exception {
        for (ActionExecutionStatus state : List.of(ActionExecutionStatus.RUNNING, ActionExecutionStatus.UNKNOWN)) {
            JdbcDataSource database = database();
            DbActionJournal setup = journal(database);
            ActionJournal.Claim original = setup.claimEffect(KEY, "click", HASH);
            if (state == ActionExecutionStatus.UNKNOWN) setup.append(terminal(original, state));
            assertFalse(journal(database).claimEffect(KEY, "click", HASH).acquired());
            assertEquals(state, setup.latest(KEY).orElseThrow().status());
        }
    }

    @Test void oldAttemptOutcomeCannotOverwriteAClaimedRetry() throws Exception {
        DbActionJournal journal = journal(database());
        ActionJournal.Claim original = journal.claim(KEY, "read", HASH, true);
        journal.append(terminal(original, ActionExecutionStatus.TIMEOUT));
        ActionJournal.Claim retry = journal.claim(KEY, "read", HASH, true);
        assertTrue(retry.acquired());
        assertThrows(IllegalStateException.class, () -> journal.append(terminal(original, ActionExecutionStatus.SUCCEEDED)));
        assertEquals(2, journal.latest(KEY).orElseThrow().attempt());
        journal.append(terminal(retry, ActionExecutionStatus.SUCCEEDED));
        assertThrows(IllegalStateException.class, () -> journal.append(terminal(retry, ActionExecutionStatus.FAILED)));
        assertEquals(ActionExecutionStatus.SUCCEEDED, journal.latest(KEY).orElseThrow().status());
    }

    @Test void runningUnknownCancelledAndSucceededReceiptsNeverBecomeAutomaticRetryPermits() throws Exception {
        for (ActionExecutionStatus status : List.of(ActionExecutionStatus.RUNNING, ActionExecutionStatus.UNKNOWN,
                ActionExecutionStatus.CANCELLED, ActionExecutionStatus.SUCCEEDED)) {
            DbActionJournal journal = journal(database());
            ActionJournal.Claim claim = journal.claim(KEY, "send", HASH, false);
            if (status != ActionExecutionStatus.RUNNING) journal.append(terminal(claim, status));
            assertFalse(journal.claim(KEY, "send", HASH, true).acquired(), status.name());
        }
    }

    @Test void failureWithoutExplicitIdempotentContractAndChangedPayloadCannotBeRetried() throws Exception {
        DbActionJournal journal = journal(database());
        ActionJournal.Claim claim = journal.claim(KEY, "send", HASH, false);
        journal.append(terminal(claim, ActionExecutionStatus.FAILED));
        assertFalse(journal.claim(KEY, "send", HASH, false).acquired());
        assertFalse(journal.claim(KEY, "other-tool", HASH, true).acquired());
        assertFalse(journal.claim(KEY, "send", "b".repeat(64), true).acquired());
        assertTrue(journal.claim(KEY, "send", HASH, true).acquired());
    }

    @Test void aPermitCommitsEvenWhenAnEnclosingApplicationTransactionRollsBack() throws Exception {
        JdbcDataSource database = database();
        DbActionJournal journal = journal(database);
        TransactionTemplate application = new TransactionTemplate(new DataSourceTransactionManager(database));
        application.executeWithoutResult(tx -> {
            assertTrue(journal.claim(KEY, "send", HASH, false).acquired());
            tx.setRollbackOnly();
        });
        assertEquals(ActionExecutionStatus.RUNNING, journal(database).latest(KEY).orElseThrow().status());
        assertFalse(journal.claim(KEY, "send", HASH, false).acquired());
    }

    @Test void databaseFailureCannotGrantExecutionOrFallBackToLocalReceipts() throws Exception {
        JdbcDataSource database = database();
        DbActionJournal journal = journal(database);
        new JdbcTemplate(database).execute("DROP TABLE agent_action_receipt");
        AtomicInteger sideEffects = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> {
            if (journal.claim(KEY, "send", HASH, false).acquired()) sideEffects.incrementAndGet();
        });
        assertEquals(0, sideEffects.get());
        assertFalse(Files.exists(directory.resolve("executor/action-journal.jsonl")));
    }

    @Test void terminalPersistenceFailureLeavesTheCommittedRunningReceiptBlockingReplay() throws Exception {
        JdbcDataSource database = database();
        DbActionJournal journal = journal(database);
        ActionJournal.Claim claim = journal.claim(KEY, "send", HASH, false);
        try (Connection connection = database.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE agent_action_receipt ADD CONSTRAINT reject_completed CHECK (execution_status='RUNNING')");
        }
        assertThrows(RuntimeException.class, () -> journal.append(terminal(claim, ActionExecutionStatus.SUCCEEDED)));
        assertEquals(ActionExecutionStatus.RUNNING, journal.latest(KEY).orElseThrow().status());
        assertFalse(journal(database).claim(KEY, "send", HASH, true).acquired());
    }

    @Test void replicaStartupDoesNotMarkAnotherReplicasRunningReceiptUnknown() throws Exception {
        JdbcDataSource database = database();
        DbActionJournal first = journal(database);
        assertTrue(first.claim(KEY, "send", HASH, false).acquired());
        Path file = directory.resolve("executor/action-journal.jsonl");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new ObjectMapper().writeValueAsString(first.latest(KEY).orElseThrow()) + "\n");
        journal(database).initialize();
        assertEquals(ActionExecutionStatus.RUNNING, first.latest(KEY).orElseThrow().status());
    }

    @Test void legacySuccessIsPreservedAndLegacyRunningImportedAsUnknownWithoutRawKeyStorage() throws Exception {
        JdbcDataSource database = database();
        Path file = directory.resolve("executor/action-journal.jsonl");
        Files.createDirectories(file.getParent());
        ActionJournalKey other = new ActionJournalKey("other-session", "execution", "send", "secret-idempotency-marker");
        ActionJournalEntry success = new ActionJournalEntry(KEY, "send", HASH, ActionExecutionStatus.SUCCEEDED,
                1, 1000L, "", "", "b".repeat(64));
        ActionJournalEntry pending = new ActionJournalEntry(other, "send", HASH, ActionExecutionStatus.RUNNING,
                1, 1001L, "", "", "");
        Files.writeString(file, new ObjectMapper().writeValueAsString(success) + "\n"
                + new ObjectMapper().writeValueAsString(pending) + "\n");
        DbActionJournal journal = journal(database);
        journal.initialize(); journal.initialize();
        assertEquals(ActionExecutionStatus.SUCCEEDED, journal.latest(KEY).orElseThrow().status());
        assertEquals(ActionExecutionStatus.UNKNOWN, journal.latest(other).orElseThrow().status());
        assertFalse(journal.claim(other, "send", HASH, true).acquired());
        assertEquals(1, journal.unresolved().size());
        String persisted = new JdbcTemplate(database).queryForList("SELECT * FROM agent_action_receipt").toString();
        assertFalse(persisted.contains("fixture-session"));
        assertFalse(persisted.contains("secret-idempotency-marker"));
    }

    @Test void corruptLegacyReceiptKeepsUiStartupAvailableButBlocksAllReceiptAccessWithoutDatabaseIo() throws Exception {
        JdbcDataSource database = database();
        Path file = directory.resolve("executor/action-journal.jsonl");
        Files.createDirectories(file.getParent());
        ActionJournalEntry success = new ActionJournalEntry(KEY, "send", HASH, ActionExecutionStatus.SUCCEEDED,
                1, 1000L, "", "", "b".repeat(64));
        String raw = new ObjectMapper().writeValueAsString(success) + "\n{\"private-fixture-marker\":\"truncated\n";
        Files.writeString(file, raw);
        DataSource monitored = spy((DataSource) database);
        DbActionJournal journal = journal(monitored);
        Logger logger = (Logger) LoggerFactory.getLogger(DbActionJournal.class);
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start(); logger.addAppender(messages);
        try {
            clearInvocations(monitored);

            assertDoesNotThrow(journal::initialize);
            assertLegacyBlocked(journal);

            verifyNoInteractions(monitored);
            assertEquals(0, new JdbcTemplate(database).queryForObject("SELECT COUNT(*) FROM agent_action_receipt", Integer.class));
            assertEquals(raw, Files.readString(file), "the damaged source must be preserved for review");
            assertEquals(1, messages.list.size());
            assertTrue(messages.list.getFirst().getFormattedMessage().contains("请人工核验历史记录"));
            assertFalse(messages.list.getFirst().getFormattedMessage().contains("private-fixture-marker"));
            assertNull(messages.list.getFirst().getThrowableProxy(), "parse causes can disclose raw contents");

            // Rewriting a file during the same process must not silently lift the block.
            Files.writeString(file, new ObjectMapper().writeValueAsString(success) + "\n");
            assertDoesNotThrow(journal::initialize);
            assertLegacyBlocked(journal);
            verifyNoInteractions(monitored);
        } finally {
            logger.detachAppender(messages); messages.stop();
        }
    }

    @Test void unreadableLegacySourceDoesNotPreventUiStartupOrAuthorizeDatabaseAccess() throws Exception {
        DataSource unavailable = mock(DataSource.class);
        Path file = directory.resolve("executor/action-journal.jsonl");
        Files.createDirectories(file); // deterministic read failure without relying on OS permissions
        DbActionJournal journal = journal(unavailable);
        clearInvocations(unavailable);

        assertDoesNotThrow(journal::initialize);
        assertLegacyBlocked(journal);

        verifyNoInteractions(unavailable);
        assertTrue(Files.isDirectory(file));
    }

    @Test void trailingJsonTokensBlockTheWholeLegacySourceBeforeAnyImport() throws Exception {
        JdbcDataSource database = database();
        Path file = directory.resolve("executor/action-journal.jsonl");
        Files.createDirectories(file.getParent());
        ActionJournalEntry success = new ActionJournalEntry(KEY, "send", HASH, ActionExecutionStatus.SUCCEEDED,
                1, 1000L, "", "", "");
        Files.writeString(file, new ObjectMapper().writeValueAsString(success) + " {}\n");
        DbActionJournal journal = journal(database);

        assertDoesNotThrow(journal::initialize);
        assertLegacyBlocked(journal);

        assertEquals(0, new JdbcTemplate(database).queryForObject("SELECT COUNT(*) FROM agent_action_receipt", Integer.class));
    }

    @Test void validLegacyImportFailureRollsBackEveryReceiptAndBlocksExecution() throws Exception {
        JdbcDataSource database = database();
        Path file = directory.resolve("executor/action-journal.jsonl");
        Files.createDirectories(file.getParent());
        ActionJournalEntry first = new ActionJournalEntry(KEY, "send", HASH, ActionExecutionStatus.SUCCEEDED,
                1, 1000L, "", "", "");
        ActionJournalEntry second = new ActionJournalEntry(new ActionJournalKey("second", "execution", "send", "key"),
                "rejected-import", HASH, ActionExecutionStatus.RUNNING, 1, 1001L, "", "", "");
        Files.writeString(file, new ObjectMapper().writeValueAsString(first) + "\n"
                + new ObjectMapper().writeValueAsString(second) + "\n");
        new JdbcTemplate(database).execute("ALTER TABLE agent_action_receipt ADD CONSTRAINT reject_import"
                + " CHECK (tool_name <> 'rejected-import')");
        DbActionJournal journal = journal(database);

        assertDoesNotThrow(journal::initialize);
        assertLegacyBlocked(journal);

        assertEquals(0, new JdbcTemplate(database).queryForObject("SELECT COUNT(*) FROM agent_action_receipt", Integer.class));
        assertEquals(List.of(), journal.unresolved(), "read-only database inspection remains available");
    }

    private static void assertLegacyBlocked(DbActionJournal journal) {
        ActionJournalEntry receipt = new ActionJournalEntry(KEY, "send", HASH, ActionExecutionStatus.SUCCEEDED,
                1, 1000L, "", "", "");
        for (org.junit.jupiter.api.function.Executable access : List.<org.junit.jupiter.api.function.Executable>of(
                () -> journal.claim(KEY, "send", HASH, false),
                () -> journal.claim(KEY, "send", HASH, true),
                () -> journal.claimEffect(KEY, "send", HASH),
                () -> journal.latest(KEY),
                () -> journal.append(receipt))) {
            IllegalStateException error = assertThrows(IllegalStateException.class, access);
            assertEquals("ACTION_JOURNAL_LEGACY_REVIEW_REQUIRED", error.getMessage());
            assertNull(error.getCause());
            assertEquals(0, error.getSuppressed().length);
        }
    }

    @Test void migrationCreatesAnEmptyDesktopAuthorityTableAndCanBeRepeated() throws Exception {
        JdbcDataSource database = database();
        migrate(database);
        assertEquals(0, new JdbcTemplate(database).queryForObject("SELECT COUNT(*) FROM agent_action_receipt", Integer.class));
    }

    @Test void rawToolDiagnosticsAndUntrustedErrorBodiesAreNeverPersisted() throws Exception {
        JdbcDataSource database = database();
        DbActionJournal journal = journal(database);
        ActionJournalEntry running = journal.claim(KEY, "send", HASH, false).entry();
        journal.append(new ActionJournalEntry(KEY, "send", HASH, ActionExecutionStatus.UNKNOWN,
                running.attempt(), System.currentTimeMillis(), "provider reply includes fixture-secret",
                "Request arguments and fixture-secret must not be persisted", "raw-output-marker"));
        String persisted = new JdbcTemplate(database).queryForList("SELECT * FROM agent_action_receipt").toString();
        assertFalse(persisted.contains("fixture-secret"));
        assertFalse(persisted.contains("raw-output-marker"));
        assertTrue(persisted.contains("UNCLASSIFIED"));
    }

    private JdbcDataSource database() throws Exception {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:action-claims-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
        migrate(database);
        return database;
    }

    private static void migrate(DataSource database) throws Exception {
        try (Connection connection = database.getConnection()) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            new V28__DurableActionClaims().migrate(context);
        }
    }

    private DbActionJournal journal(DataSource database) {
        return new DbActionJournal(database, new DataSourceTransactionManager(database), new AgentDataPaths(directory.toString()));
    }

    private static ActionJournalEntry terminal(ActionJournal.Claim claim, ActionExecutionStatus status) {
        ActionJournalEntry entry = claim.entry();
        return new ActionJournalEntry(entry.key(), entry.toolName(), entry.argumentsHash(), status,
                entry.attempt(), System.currentTimeMillis(), "", "", "b".repeat(64));
    }

    private static List<ActionJournal.Claim> race(Callable<ActionJournal.Claim> first,
                                                 Callable<ActionJournal.Claim> second) throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(first); var two = workers.submit(second);
            return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        }
    }

    /** Both transactions actually observe the same old state before either may mutate it. */
    private static DataSource synchronizedReads(DataSource database) {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        return new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException { return wrap(database.getConnection()); }
            @Override public Connection getConnection(String user, String password) throws SQLException {
                return wrap(database.getConnection(user, password));
            }
            private Connection wrap(Connection connection) {
                return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            Object result = invoke(connection, method, args);
                            if (!method.getName().equals("prepareStatement") || args == null
                                    || !(args[0] instanceof String sql) || !sql.startsWith("SELECT ")
                                    || !sql.contains("WHERE key_digest=?")) return result;
                            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                    (statementProxy, statementMethod, statementArgs) -> {
                                        Object value = invoke(result, statementMethod, statementArgs);
                                        if (statementMethod.getName().equals("executeQuery") && reads.incrementAndGet() <= 2)
                                            barrier.await(5, TimeUnit.SECONDS);
                                        return value;
                                    });
                        });
            }
        };
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException exception) { throw exception.getCause(); }
    }
}
