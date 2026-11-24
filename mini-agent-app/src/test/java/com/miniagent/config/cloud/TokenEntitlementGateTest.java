package com.miniagent.config.cloud;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 额度闸门的判定、缓存与降级。
 *
 * <p>三条语义必须成立：</p>
 * <ol>
 *   <li>超额返回可展示的文案，不返回 null —— null 会被上游当成「允许」</li>
 *   <li>5 分钟内复用缓存，不重复打账号服务（一个任务会多次触发准入检查）</li>
 *   <li>账号服务不可达时<b>放行</b>，且不写缓存 —— 写缓存会让一次抖动在 5 分钟内被当成「已经确认」</li>
 * </ol>
 */
class TokenEntitlementGateTest {

    private static final Long USER = 42L;

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CloudMembershipClient> provider(CloudMembershipClient client) {
        ObjectProvider<CloudMembershipClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    private static CloudMembershipClient.EntitlementView view(boolean allowed, String reason) {
        return new CloudMembershipClient.EntitlementView(
                allowed, "free", 200_000L, allowed ? 10_000L : 250_000L,
                allowed ? 190_000L : 0L, "2026-11-25", reason);
    }

    @Test
    void missingCloudClientAllows() {
        TokenEntitlementGate gate = new TokenEntitlementGate(provider(null));
        assertNull(gate.rejectionReason(USER));
    }

    @Test
    void unconfiguredClientAllows() {
        CloudMembershipClient client = mock(CloudMembershipClient.class);
        when(client.available()).thenReturn(false);

        TokenEntitlementGate gate = new TokenEntitlementGate(provider(client));
        assertNull(gate.rejectionReason(USER));
        verify(client, times(0)).entitlement(anyLong());
    }

    @Test
    void allowedReturnsNull() {
        CloudMembershipClient client = mock(CloudMembershipClient.class);
        when(client.available()).thenReturn(true);
        when(client.entitlement(USER)).thenReturn(view(true, ""));

        TokenEntitlementGate gate = new TokenEntitlementGate(provider(client));
        assertNull(gate.rejectionReason(USER));
    }

    @Test
    void overQuotaReturnsServiceReason() {
        CloudMembershipClient client = mock(CloudMembershipClient.class);
        when(client.available()).thenReturn(true);
        when(client.entitlement(USER)).thenReturn(view(false, "今日 token 额度已用完（250000 / 200000）"));

        TokenEntitlementGate gate = new TokenEntitlementGate(provider(client));
        assertEquals("今日 token 额度已用完（250000 / 200000）", gate.rejectionReason(USER));
    }

    /** 服务端没给文案时也要能说清是哪条额度、什么时候恢复。 */
    @Test
    void blankReasonFallsBackToBuiltInText() {
        CloudMembershipClient client = mock(CloudMembershipClient.class);
        when(client.available()).thenReturn(true);
        when(client.entitlement(USER)).thenReturn(view(false, "  "));

        TokenEntitlementGate gate = new TokenEntitlementGate(provider(client));
        String reason = gate.rejectionReason(USER);
        assertTrue(reason.contains("250000") && reason.contains("200000"));
        assertTrue(reason.contains("2026-11-25"));
    }

    @Test
    void cachePreventsRepeatedRemoteCalls() {
        CloudMembershipClient client = mock(CloudMembershipClient.class);
        when(client.available()).thenReturn(true);
        when(client.entitlement(USER)).thenReturn(view(false, "超额"));

        TokenEntitlementGate gate = new TokenEntitlementGate(provider(client));
        gate.rejectionReason(USER);
        gate.rejectionReason(USER);
        gate.rejectionReason(USER);

        // 三次判定只应打一次账号服务。
        verify(client, times(1)).entitlement(USER);
    }

    @Test
    void unreachableFailsOpenAndDoesNotCache() {
        CloudMembershipClient client = mock(CloudMembershipClient.class);
        when(client.available()).thenReturn(true);
        when(client.entitlement(USER)).thenThrow(new RuntimeException("connect timed out"));

        TokenEntitlementGate gate = new TokenEntitlementGate(provider(client));
        assertNull(gate.rejectionReason(USER));
        assertEquals(1, gate.failOpenCount());

        // 不缓存：下次还会再试，而不是在 5 分钟里假装「已经放行」。
        assertNull(gate.rejectionReason(USER));
        assertEquals(2, gate.failOpenCount());
        verify(client, times(2)).entitlement(USER);
    }

    @Test
    void invalidateForcesRecheck() {
        CloudMembershipClient client = mock(CloudMembershipClient.class);
        when(client.available()).thenReturn(true);
        when(client.entitlement(USER)).thenReturn(view(false, "超额"));

        TokenEntitlementGate gate = new TokenEntitlementGate(provider(client));
        gate.rejectionReason(USER);
        gate.invalidate(USER);
        gate.rejectionReason(USER);

        verify(client, times(2)).entitlement(USER);
    }
}
