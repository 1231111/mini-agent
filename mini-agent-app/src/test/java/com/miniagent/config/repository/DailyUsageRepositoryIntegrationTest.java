package com.miniagent.config.repository;

import com.miniagent.config.entity.Tenant;
import com.miniagent.config.entity.User;
import com.miniagent.config.entity.UserRole;
import com.miniagent.agent.core.TenantTokenQuota;
import com.miniagent.config.service.DbTenantTokenQuota;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises portable daily-usage increments against the desktop H2/MySQL-mode database. */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.datasource.url=jdbc:h2:mem:daily-usage;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000"
})
@Import({DbTenantTokenQuota.class, DailyUsageRepositoryIntegrationTest.MetricsConfiguration.class})
@org.springframework.transaction.annotation.Transactional(propagation = Propagation.NOT_SUPPORTED)
class DailyUsageRepositoryIntegrationTest {

    @Autowired private TenantDailyUsageRepository tenantUsage;
    @Autowired private UserDailyUsageRepository userUsage;
    @Autowired private TenantRepository tenants;
    @Autowired private UserRepository users;
    @Autowired private TenantTokenQuota quota;
    @Autowired private PlatformTransactionManager transactionManager;

    @TestConfiguration(proxyBeanMethods = false)
    static class MetricsConfiguration {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Test
    void quotaServiceOverloadsCreateTransactionsWithoutAmbientTransaction() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Tenant tenant = new Tenant();
        tenant.setSlug("quota-proxy-" + suffix);
        tenant.setName("Quota proxy test");
        tenant = tenants.saveAndFlush(tenant);

        User user = new User();
        user.setUsername("quota-proxy-" + suffix);
        user.setPasswordHash("test-hash");
        user.setTenantId(tenant.getId());
        user.setRole(UserRole.USER);
        user = users.saveAndFlush(user);

        assertTrue(quota.consume(tenant.getId(), user.getId(), 7L,
                UUID.randomUUID().toString(), 5L, 2L).allowed());
        assertTrue(quota.consume(tenant.getId(), user.getId(), 11L).allowed());

        LocalDate today = LocalDate.now();
        assertEquals(18L, tenantUsage.tokenCount(tenant.getId(), today).orElseThrow());
        assertEquals(18L, userUsage.tokenCount(user.getId(), today).orElseThrow());
    }

    @Test
    void firstInsertAndConcurrentIncrementsRemainPortableAndUnique() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Tenant tenant = new Tenant();
        tenant.setSlug("quota-" + suffix);
        tenant.setName("Quota test");
        tenant.setDailyTokenLimit(1_000_000);
        tenant.setDailyUserTokenLimit(1_000_000);
        tenant = tenants.saveAndFlush(tenant);

        User user = new User();
        user.setUsername("quota-" + suffix);
        user.setPasswordHash("test-hash");
        user.setTenantId(tenant.getId());
        user.setRole(UserRole.USER);
        user = users.saveAndFlush(user);

        Long tenantId = tenant.getId();
        Long userId = user.getId();
        LocalDate usageDate = LocalDate.now();
        int workers = 8;
        int incrementsPerWorker = 20;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        try (var pool = Executors.newFixedThreadPool(workers)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("workers did not start");
                    }
                    for (int n = 0; n < incrementsPerWorker; n++) {
                        transaction.executeWithoutResult(status -> {
                            tenantUsage.increment(tenantId, usageDate, 3);
                            userUsage.increment(userId, usageDate, 3);
                        });
                    }
                    return null;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (var future : futures) future.get(30, TimeUnit.SECONDS);
        }

        long expected = (long) workers * incrementsPerWorker * 3;
        assertEquals(expected, tenantUsage.tokenCount(tenantId, usageDate).orElseThrow());
        assertEquals(expected, userUsage.tokenCount(userId, usageDate).orElseThrow());
        assertEquals(1, tenantUsage.countByTenantIdAndUsageDate(tenantId, usageDate));
        assertEquals(1, userUsage.countByUserIdAndUsageDate(userId, usageDate));
    }
}
