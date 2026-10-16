package com.miniagent.account.service;

import com.miniagent.account.entity.MembershipPlan;
import com.miniagent.account.entity.MembershipSubscription;
import com.miniagent.account.entity.Tenant;
import com.miniagent.account.repository.MembershipOrderRepository;
import com.miniagent.account.repository.MembershipPlanRepository;
import com.miniagent.account.repository.MembershipSubscriptionRepository;
import com.miniagent.account.repository.MembershipUsageEventRepository;
import com.miniagent.account.repository.TenantRepository;
import com.miniagent.account.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks the ordering contract that serializes concurrent plan changes.  The
 * database lock itself is exercised by the repository integration tests; this
 * unit test makes the easy-to-regress read-before-lock mistake visible in code
 * review and on every build.
 */
class MembershipServiceConcurrencyTest {

    @Test
    void tenantIsLockedBeforeActiveSubscriptionsAreRead() {
        MembershipPlanRepository plans = mock(MembershipPlanRepository.class);
        MembershipSubscriptionRepository subscriptions = mock(MembershipSubscriptionRepository.class);
        MembershipOrderRepository orders = mock(MembershipOrderRepository.class);
        TenantRepository tenants = mock(TenantRepository.class);
        UserRepository users = mock(UserRepository.class);
        MembershipUsageEventRepository usageEvents = mock(MembershipUsageEventRepository.class);

        MembershipService service = new MembershipService();
        ReflectionTestUtils.setField(service, "plans", plans);
        ReflectionTestUtils.setField(service, "subscriptions", subscriptions);
        ReflectionTestUtils.setField(service, "orders", orders);
        ReflectionTestUtils.setField(service, "tenants", tenants);
        ReflectionTestUtils.setField(service, "users", users);
        ReflectionTestUtils.setField(service, "usageEvents", usageEvents);

        long tenantId = 41L;
        long userId = 7L;
        Tenant tenant = new Tenant();
        MembershipSubscription old = new MembershipSubscription();
        old.setStatus(MembershipSubscription.Status.ACTIVE);
        MembershipPlan plan = new MembershipPlan();
        plan.setCode("pro");
        plan.setDailyTokenLimit(5_000_000L);
        plan.setDurationDays(30);

        when(tenants.findByIdForUpdate(tenantId)).thenReturn(Optional.of(tenant));
        when(subscriptions.findByUserIdAndStatus(userId, MembershipSubscription.Status.ACTIVE))
                .thenReturn(List.of(old));
        when(subscriptions.save(any(MembershipSubscription.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        MembershipSubscription created = service.applyPlan(userId, tenantId, plan, "order-1");

        assertThat(created.getStatus()).isEqualTo(MembershipSubscription.Status.ACTIVE);
        assertThat(tenant.getDailyTokenLimit()).isEqualTo(5_000_000L);
        var calls = inOrder(tenants, subscriptions);
        calls.verify(tenants).findByIdForUpdate(tenantId);
        calls.verify(subscriptions).findByUserIdAndStatus(userId, MembershipSubscription.Status.ACTIVE);
    }
}
