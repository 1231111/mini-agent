package com.miniagent.config.flyway;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.sql.Connection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopFlywayFreshInstallTest {

    @Test
    void freshDesktopDatabaseBaselinesAfterHibernateCreatesSchema() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:desktop_fresh_install;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:com/miniagent/migration")
                .baselineVersion("8")
                .load();
        MigrationVersion latestResolved = java.util.Arrays.stream(flyway.info().all())
                .map(org.flywaydb.core.api.MigrationInfo::getVersion)
                .filter(java.util.Objects::nonNull)
                .max(MigrationVersion::compareTo)
                .orElseThrow();

        // Reproduce the desktop startup order: Hibernate creates entity-backed
        // tables first; Flyway then establishes the baseline and applies later migrations.
        LocalContainerEntityManagerFactoryBean entityManagerFactory =
                new LocalContainerEntityManagerFactoryBean();
        entityManagerFactory.setDataSource(dataSource);
        entityManagerFactory.setPackagesToScan("com.miniagent.config.entity",
                "com.miniagent.agent.memory.entity");
        entityManagerFactory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        entityManagerFactory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "update",
                "hibernate.dialect", "org.hibernate.dialect.H2Dialect",
                "hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
        entityManagerFactory.afterPropertiesSet();
        try {
            assertThat(tableExists(dataSource, "tenants")).isTrue();
            assertThat(tableExists(dataSource, "chat_messages")).isTrue();

            new DesktopFlywayConfig().desktopFlywayPostJpaInitializer(flyway, dataSource)
                    .afterPropertiesSet();

            assertThat(flyway.info().current()).isNotNull();
            assertThat(flyway.info().current().getVersion()).isEqualTo(latestResolved);
            assertThat(flyway.info().pending()).isEmpty();
            assertThat(tableExists(dataSource, "memory_subject_erasure")).isTrue();
            assertThat(tableExists(dataSource, "agent_action_receipt")).isTrue();
        } finally {
            entityManagerFactory.destroy();
        }
    }

    private static boolean tableExists(DriverManagerDataSource dataSource, String expected) throws Exception {
        try (Connection connection = dataSource.getConnection();
             var tables = connection.getMetaData().getTables(
                     connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String schema = tables.getString("TABLE_SCHEM");
                if (schema != null && schema.equalsIgnoreCase("information_schema")) continue;
                if (expected.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
            }
        }
        return false;
    }
}
