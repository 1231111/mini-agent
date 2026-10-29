package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProcedureRewriteProposalMigrationTest {
    @Test
    void createsProposalTableIndexesAndSourceUniquenessOnH2MysqlMode() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:proposal-migration;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);

            V14__ProcedureRewriteProposals migration = new V14__ProcedureRewriteProposals();
            migration.migrate(context);

            assertEquals(1, count(connection, "SELECT COUNT(*) AS c FROM information_schema.tables "
                    + "WHERE lower(table_name) = 'agent_procedure_rewrite_proposals'"));

            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO agent_procedure_rewrite_proposals "
                        + "(tenant_id,user_id,procedure_id,base_version,episode_id,procedure_name,"
                        + "base_content_json,proposed_content_json,evidence_json) "
                        + "VALUES ('tenant-a',9,1,1,2,'p','{}','{}','{}')");
                assertThrows(Exception.class, () -> statement.execute("INSERT INTO "
                        + "agent_procedure_rewrite_proposals (tenant_id,user_id,procedure_id,base_version,"
                        + "episode_id,procedure_name,base_content_json,proposed_content_json,evidence_json) "
                        + "VALUES ('tenant-a',9,1,1,2,'p','{}','{}','{}')"));
            }

            migration.migrate(context);
            assertEquals(1, count(connection, "SELECT COUNT(*) AS c FROM agent_procedure_rewrite_proposals"));
        }
    }

    @Test
    void skipsWhenTableAlreadyExistsAndWhenItDoesNotExist() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:proposal-migration-existing;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            V14__ProcedureRewriteProposals migration = new V14__ProcedureRewriteProposals();
            migration.migrate(context);
            migration.migrate(context);
            assertEquals(1, count(connection, "SELECT COUNT(*) AS c FROM information_schema.tables "
                    + "WHERE lower(table_name) = 'agent_procedure_rewrite_proposals'"));
        }
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:proposal-migration-empty;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            new V14__ProcedureRewriteProposals().migrate(context);
        }
    }

    private int count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt("c");
        }
    }
}
