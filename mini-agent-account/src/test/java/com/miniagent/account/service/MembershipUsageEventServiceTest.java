package com.miniagent.account.service;

import com.miniagent.account.entity.Tenant;
import com.miniagent.account.entity.User;
import com.miniagent.account.repository.MembershipUsageEventRepository;
import com.miniagent.account.repository.TenantRepository;
import com.miniagent.account.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({MembershipService.class, MembershipUsageEventService.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"
})
class MembershipUsageEventServiceTest {

    @Autowired
    private MembershipUsageEventService usageService;
    @Autowired
    private MembershipService membership;
    @Autowired
    private MembershipUsageEventRepository usageEvents;
    @Autowired
    private TenantRepository tenants;
    @Autowired
    private UserRepository users;

    @Test
    void duplicateEventIdForSameUserIsCountedOnlyOnce() {
        User user = createUser("usage-duplicate");

        assertThat(usageService.recordUsage(user.getId(), 10, 4, 1, "llm-call-1")).isTrue();
        // A retried report may even have a changed payload; the first accepted event wins.
        assertThat(usageService.recordUsage(user.getId(), 100, 40, 1, "llm-call-1")).isFalse();

        var view = membership.usageOf(user.getId());
        assertThat(usageEvents.countByUserId(user.getId())).isEqualTo(1);
        assertThat(view.inputTokens()).isEqualTo(10);
        assertThat(view.outputTokens()).isEqualTo(4);
        assertThat(view.llmCalls()).isEqualTo(1);
    }

    @Test
    void reportsWithoutEventIdRemainAppendOnlyForLegacyClients() {
        User user = createUser("usage-legacy");

        usageService.recordUsage(user.getId(), 10, 4, 1, null);
        usageService.recordUsage(user.getId(), 3, 1, 1, null);

        assertThat(usageEvents.countByUserId(user.getId())).isEqualTo(2);
        assertThat(membership.usageOf(user.getId()).llmCalls()).isEqualTo(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentReportsWithSameEventIdHaveOneDatabaseWinner() throws Exception {
        User user = createUser("usage-race");
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> {
                start.await();
                return usageService.recordUsage(user.getId(), 10, 4, 1, "shared-event");
            });
            Future<Boolean> second = pool.submit(() -> {
                start.await();
                return usageService.recordUsage(user.getId(), 10, 4, 1, "shared-event");
            });
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS)).isNotEqualTo(second.get(10, TimeUnit.SECONDS));
            assertThat(usageEvents.countByUserId(user.getId())).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private User createUser(String username) {
        Tenant tenant = new Tenant();
        tenant.setSlug("tenant-" + username);
        tenant.setName(username);
        tenant.setDailyTokenLimit(100_000);
        tenant = tenants.saveAndFlush(tenant);

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash("test-hash");
        user.setTenantId(tenant.getId());
        return users.saveAndFlush(user);
    }
}
