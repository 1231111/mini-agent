package com.miniagent.migration;

import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrustedProjectsAndProcedureHistoryMigrationTest {
    @Test
    void createsAllTablesAndConversationColumnIdempotentlyOnH2MysqlMode() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:trusted-project-migration;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE chat_conversations (id VARCHAR(100) PRIMARY KEY)");
            }
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            V15__TrustedProjectsAndProcedureHistory migration = new V15__TrustedProjectsAndProcedureHistory();

            migration.migrate(context);
            migration.migrate(context);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE agent_procedure_rewrite_proposals (id BIGINT PRIMARY KEY)");
            }
            V16__ProcedureCandidateValidations validations = new V16__ProcedureCandidateValidations();
            validations.migrate(context);
            validations.migrate(context);

            for (String table : new String[]{"agent_projects", "agent_project_members",
                    "agent_procedure_revisions", "experience_clusters", "experience_cluster_members",
                    "agent_procedure_candidate_validations"}) {
                assertEquals(1, count(connection, "SELECT COUNT(*) AS c FROM information_schema.tables "
                        + "WHERE lower(table_name)='" + table + "'"), table + " created once");
            }
            assertEquals(1, count(connection, "SELECT COUNT(*) AS c FROM information_schema.columns "
                    + "WHERE lower(table_name)='chat_conversations' AND lower(column_name)='project_id'"));
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO agent_projects(project_id,tenant_id,owner_user_id,project_name) "
                        + "VALUES('p1','t1',7,'Project')");
                statement.execute("INSERT INTO agent_project_members(project_id,user_id) VALUES('p1',8)");
                statement.execute("INSERT INTO experience_clusters(tenant_id,owner_user_id,scope_type,scope_id,cluster_key,label) "
                        + "VALUES('t1',7,'PROJECT','p1','k','K')");
                statement.execute("INSERT INTO agent_procedure_revisions(tenant_id,procedure_id,procedure_version,content_hash,content_json,source_type) "
                        + "VALUES('t1',1,1,'hash','{}','CREATE')");
            }
        }
    }

    private int count(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt("c");
        }
    }
}
