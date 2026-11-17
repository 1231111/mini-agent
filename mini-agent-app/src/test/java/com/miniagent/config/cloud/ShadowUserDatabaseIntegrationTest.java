package com.miniagent.config.cloud;

import com.miniagent.common.ErrorCode;
import com.miniagent.config.entity.Tenant;
import com.miniagent.config.entity.User;
import com.miniagent.config.entity.UserRole;
import com.miniagent.config.repository.TenantRepository;
import com.miniagent.config.repository.UserRepository;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Real isolated H2, Spring Data and transaction advice; no application startup or network. */
class ShadowUserDatabaseIntegrationTest {
    @Test
    void desktopLoginCreatesAnExternalIdentityThenReusesItAndHonorsLocalDisablement() {
        try (Fixture fixture = new Fixture(false)) {
            assertTrue(fixture.read(() -> fixture.users.findById(2L).isEmpty()));
            User first = fixture.login();
            Long localId = first.getId();
            assertNotNull(localId);
            assertNotEquals(2L, localId, "The cloud ID is not the local database primary key");
            assertEquals("2", first.getExternalId());
            assertEquals(fixture.systemTenantId, first.getTenantId());
            assertEquals(UserRole.USER, first.getRole(), "Cloud administrator roles cannot grant local administration");

            // Read in a fresh transaction: this must be committed, not only returned by a managed entity.
            User persisted = fixture.read(() -> fixture.users.findByExternalIdForUpdate("2").orElseThrow());
            assertEquals(localId, persisted.getId());
            assertTrue(fixture.read(() -> fixture.users.findById(2L).isEmpty()));
            assertEquals(1L, fixture.countUsers());

            fixture.identity = identity("Renamed in cloud");
            User repeated = fixture.login();
            assertEquals(localId, repeated.getId());
            assertEquals(1L, fixture.countUsers(), "Repeated login must not create a second local identity");
            assertEquals("Renamed in cloud", fixture.read(() -> fixture.users.findById(localId).orElseThrow().getDisplayName()));

            fixture.write(() -> {
                User shadow = fixture.users.findById(localId).orElseThrow();
                shadow.setEnabled(false);
                fixture.users.saveAndFlush(shadow);
            });
            CloudAccountException denied = assertThrows(CloudAccountException.class, fixture::login);
            assertEquals(ErrorCode.AUTH_FORBIDDEN, denied.errorCode());
            assertEquals(1L, fixture.countUsers());
            assertFalse(fixture.read(() -> fixture.users.findById(localId).orElseThrow().isEnabled()));
            verify(fixture.client, times(3)).login(anyString(), anyString());
        }
    }

    @Test
    void aMatchingLocalPrimaryKeyAndUsernameCannotBeTakenOverByTheCloudAccount() {
        try (Fixture fixture = new Fixture(false)) {
            Long ownerTenant = fixture.createTenant("owner-private");
            User firstLocal = fixture.createLocal("local-first", "First local owner", ownerTenant, UserRole.USER);
            User localOwner = fixture.createLocal("cloud-user", "Original local owner", ownerTenant, UserRole.SYSTEM_ADMIN);
            assertEquals(1L, firstLocal.getId());
            assertEquals(2L, localOwner.getId(), "Reproduce the dangerous collision with the cloud's userId=2");
            assertNull(localOwner.getExternalId());

            User shadow = fixture.login();
            assertNotEquals(localOwner.getId(), shadow.getId());
            assertEquals("2", shadow.getExternalId());
            assertEquals("cloud-user#2", shadow.getUsername());
            assertEquals(fixture.systemTenantId, shadow.getTenantId());
            assertEquals(UserRole.USER, shadow.getRole());
            assertEquals(3L, fixture.countUsers());

            User stillLocal = fixture.read(() -> fixture.users.findById(2L).orElseThrow());
            assertEquals("cloud-user", stillLocal.getUsername());
            assertEquals("Original local owner", stillLocal.getDisplayName());
            assertNull(stillLocal.getExternalId());
            assertEquals(ownerTenant, stillLocal.getTenantId());
            assertEquals(UserRole.SYSTEM_ADMIN, stillLocal.getRole());
            assertTrue(stillLocal.isEnabled());
            assertEquals(localOwner.getId(), fixture.read(() -> fixture.users.findByUsername("cloud-user").orElseThrow().getId()));

            assertEquals(shadow.getId(), fixture.login().getId());
            assertEquals(3L, fixture.countUsers());
            verify(fixture.client, times(2)).login(anyString(), anyString());
        }
    }

    @Test
    void sharedDatabaseLoginReusesTheAccountRowAndRejectsMissingRowsWithoutCreatingShadows() {
        try (Fixture fixture = new Fixture(true)) {
            fixture.createLocal("first-shared-account", "First account", fixture.systemTenantId, UserRole.USER);
            User shared = fixture.createLocal("cloud-user", "Existing shared account", fixture.systemTenantId, UserRole.TENANT_ADMIN);
            assertEquals(2L, shared.getId());

            User loggedIn = fixture.login();
            assertEquals(shared.getId(), loggedIn.getId());
            assertEquals(UserRole.TENANT_ADMIN, loggedIn.getRole());
            assertNull(loggedIn.getExternalId(), "Shared rows must not gain a second shadow mapping");
            assertEquals(2L, fixture.countUsers());
            assertEquals(shared.getId(), fixture.login().getId());
            assertEquals(2L, fixture.countUsers());
            assertEquals("Cloud display", fixture.read(() -> fixture.users.findById(2L).orElseThrow().getDisplayName()));

            // A real committed absence reproduces an agent/account database mismatch.
            fixture.write(() -> fixture.users.deleteById(2L));
            assertTrue(fixture.read(() -> fixture.users.findById(2L).isEmpty()));
            CloudAccountException missing = assertThrows(CloudAccountException.class, fixture::login);
            assertEquals(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE, missing.errorCode());
            assertTrue(missing.getMessage().contains("users.id=2"));
            assertEquals(1L, fixture.countUsers(), "Shared mode must not hide a wrong database by creating another user");
            assertTrue(fixture.read(() -> fixture.users.findByExternalIdForUpdate("2").isEmpty()));
            verify(fixture.client, times(3)).login(anyString(), anyString());
        }
    }

    @Test
    void newlyInitializedDesktopSchemaRejectsASecondRowForTheSameCloudIdentity() {
        try (Fixture fixture = new Fixture(false)) {
            User first = fixture.login();
            RuntimeException duplicate = assertThrows(RuntimeException.class, () -> fixture.write(() -> {
                User another = new User();
                another.setUsername("another-local-name");
                another.setExternalId("2");
                another.setPasswordHash("fixture-only-local-hash");
                another.setTenantId(fixture.systemTenantId);
                fixture.users.saveAndFlush(another);
            }));
            Throwable cause = duplicate;
            while (cause != null && !(cause instanceof java.sql.SQLException)) cause = cause.getCause();
            assertNotNull(cause, "The database constraint, rather than application name lookup, must reject the duplicate");
            assertEquals("23505", ((java.sql.SQLException) cause).getSQLState());
            assertEquals(1L, fixture.countUsers());
            assertEquals(first.getId(), fixture.read(() -> fixture.users.findByExternalIdForUpdate("2").orElseThrow().getId()));
        }
    }

    private static CloudUser identity(String displayName) {
        return new CloudUser("2", "cloud-user", displayName, "SYSTEM_ADMIN", "cloud-tenant", null, null);
    }

    private static final class Fixture implements AutoCloseable {
        final SessionFactory sessions;
        final TransactionTemplate transactions;
        final UserRepository users;
        final TenantRepository tenants;
        final CloudAccountClient client = mock(CloudAccountClient.class);
        final CloudAccountService accounts;
        final Long systemTenantId;
        CloudUser identity = identity("Cloud display");

        Fixture(boolean sharedDatabase) {
            sessions = new Configuration().addAnnotatedClass(User.class).addAnnotatedClass(Tenant.class)
                    .setProperty("hibernate.connection.driver_class", "org.h2.Driver")
                    .setProperty("hibernate.connection.url", "jdbc:h2:mem:shadow-identity-" + UUID.randomUUID()
                            + ";MODE=MySQL;DB_CLOSE_DELAY=-1")
                    .setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
                    .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                    .buildSessionFactory();
            var entityManager = SharedEntityManagerCreator.createSharedEntityManager(sessions);
            JpaRepositoryFactory repositories = new JpaRepositoryFactory(entityManager);
            users = repositories.getRepository(UserRepository.class);
            tenants = repositories.getRepository(TenantRepository.class);
            JpaTransactionManager manager = new JpaTransactionManager(sessions);
            transactions = new TransactionTemplate(manager);
            systemTenantId = createTenant("system");
            if (sharedDatabase) {
                identity = new CloudUser("2", "cloud-user", "Cloud display", "SYSTEM_ADMIN",
                        systemTenantId.toString(), null, null);
            }

            ShadowUserService target = new ShadowUserService();
            ReflectionTestUtils.setField(target, "userRepository", users);
            ReflectionTestUtils.setField(target, "tenants", tenants);
            ReflectionTestUtils.setField(target, "passwordEncoder", new BCryptPasswordEncoder(4));
            ReflectionTestUtils.setField(target, "sharedDatabase", sharedDatabase);
            ReflectionTestUtils.setField(target, "adoptRole", false);
            ProxyFactory proxyFactory = new ProxyFactory(target);
            proxyFactory.setProxyTargetClass(true);
            proxyFactory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
            ShadowUserService shadowUsers = (ShadowUserService) proxyFactory.getProxy();
            assertTrue(AopUtils.isAopProxy(shadowUsers), "The service's @Transactional boundary must really execute");

            accounts = new CloudAccountService();
            ReflectionTestUtils.setField(accounts, "client", client);
            ReflectionTestUtils.setField(accounts, "shadowUsers", shadowUsers);
            when(client.login(anyString(), anyString())).thenAnswer(invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                        "The remote identity request must remain outside the database transaction");
                return identity;
            });
        }

        User login() { return accounts.login("fixture-login", "fixture-only-password"); }
        long countUsers() { return read(users::count); }
        <T> T read(Supplier<T> operation) { return transactions.execute(status -> operation.get()); }
        void write(Runnable operation) { transactions.executeWithoutResult(status -> operation.run()); }

        Long createTenant(String slug) {
            return read(() -> {
                Tenant tenant = new Tenant();
                tenant.setSlug(slug);
                tenant.setName(slug);
                tenant.setEnabled(true);
                return tenants.saveAndFlush(tenant).getId();
            });
        }

        User createLocal(String username, String displayName, Long tenantId, UserRole role) {
            return read(() -> {
                User user = new User();
                user.setUsername(username);
                user.setDisplayName(displayName);
                user.setPasswordHash("fixture-only-local-hash");
                user.setTenantId(tenantId);
                user.setRole(role);
                user.setEnabled(true);
                return users.saveAndFlush(user);
            });
        }

        @Override public void close() { sessions.close(); }
    }
}
