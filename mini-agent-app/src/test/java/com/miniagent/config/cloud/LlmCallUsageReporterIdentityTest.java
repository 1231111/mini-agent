package com.miniagent.config.cloud;

import com.miniagent.agent.core.LlmCallUsageSink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 上报时用户身份必须来自采集点，而不是投递线程的 ThreadLocal。
 *
 * <p>这条断言防的是一个静默回归：把取 userId 放回 {@code OwnerContext.currentUserId()}
 * 之后，单测仍然通过（测试线程没有上下文 → 直接 return），但线上所有调用都会悄无声息地不记。</p>
 */
class LlmCallUsageReporterIdentityTest {

    private static LlmCallUsageSink.CallUsage usage(Long userId) {
        return new LlmCallUsageSink.CallUsage(
                userId, "s_abc", 3, "req-1", "deepseek-flash", "Xhigh", "/chat/completions",
                true, "TOKEN", "abcd",
                19_666L, 19_584L, 82L, 0L, 1_200L, 3_400L, "test-agent");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CloudMembershipClient> provider(CloudMembershipClient client) {
        ObjectProvider<CloudMembershipClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

    @Test
    void userIdComesFromUsageNotThreadLocal() throws Exception {
        CloudMembershipClient client = mock(CloudMembershipClient.class);
        when(client.available()).thenReturn(true);

        CountDownLatch sent = new CountDownLatch(1);
        AtomicReference<Map<String, Object>> payload = new AtomicReference<>();
        doAnswer(invocation -> {
            payload.set(invocation.getArgument(0));
            sent.countDown();
            return null;
        }).when(client).reportCallUsage(any());

        LlmCallUsageReporter reporter = new LlmCallUsageReporter(provider(client),
                new ConfiguredModelPricing(new ModelPricingProperties()));

        // 当前线程没有任何 OwnerContext —— 旧实现会在这里直接 return。
        reporter.record(usage(42L));

        assertTrue(sent.await(3, TimeUnit.SECONDS), "上报未被提交，可能是 userId 取不到导致的静默跳过");
        assertNotNull(payload.get());
        assertEquals(42L, payload.get().get("userId"));
        assertEquals(19_584L, payload.get().get("cachedInputTokens"));
        assertEquals("deepseek-flash", payload.get().get("model"));
    }

    @Test
    void missingUserIdIsSkippedSilently() throws Exception {
        CloudMembershipClient client = mock(CloudMembershipClient.class);
        when(client.available()).thenReturn(true);

        LlmCallUsageReporter reporter = new LlmCallUsageReporter(provider(client),
                new ConfiguredModelPricing(new ModelPricingProperties()));

        reporter.record(usage(null));

        // 没有用户就无法归属，跳过是正确行为；这里只确认它不抛异常、不写脏数据。
        org.mockito.Mockito.verify(client, org.mockito.Mockito.after(300).never())
                .reportCallUsage(any());
    }
}
