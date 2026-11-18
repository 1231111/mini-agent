package com.miniagent.account.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptRateLimitFilterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void appliesGlobalAccountLimitAcrossDifferentIpsAndReturnsRetryAfter() throws Exception {
        LoginAttemptRateLimitFilter filter = filter(2, 20, "");
        AtomicInteger passed = new AtomicInteger();
        FilterChain chain = (request, response) -> passed.incrementAndGet();

        invoke(filter, "/api/tokens", "198.51.100.1", "{\"username\":\"Ａlice\"}", chain);
        invoke(filter, "/api/portal/session", "198.51.100.2", "{\"username\":\"alice\"}", chain);
        MockHttpServletResponse rejected = invoke(filter, "/api/tokens", "198.51.100.3",
                "{\"username\":\"ALICE\"}", chain);

        assertThat(passed).hasValue(2);
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isNotBlank();
    }

    @Test
    void capsAggregateIpAttemptsAcrossAccountsAndIgnoresUntrustedForwardedFor() throws Exception {
        LoginAttemptRateLimitFilter filter = filter(10, 2, "");
        AtomicInteger passed = new AtomicInteger();
        FilterChain chain = (request, response) -> passed.incrementAndGet();

        invoke(filter, "/api/tokens", "192.0.2.10", "{\"username\":\"alice\"}", "198.51.100.1", chain);
        invoke(filter, "/api/tokens", "192.0.2.10", "{\"username\":\"bob\"}", "203.0.113.1", chain);
        MockHttpServletResponse rejected = invoke(filter, "/api/tokens", "192.0.2.10",
                "{\"username\":\"carol\"}", "203.0.113.2", chain);

        assertThat(passed).hasValue(2);
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isNotBlank();
    }

    @Test
    void trustsForwardedAddressOnlyFromConfiguredProxy() throws Exception {
        LoginAttemptRateLimitFilter filter = filter(10, 1, "192.0.2.1");
        AtomicInteger passed = new AtomicInteger();
        FilterChain chain = (request, response) -> passed.incrementAndGet();
        invoke(filter, "/api/tokens", "192.0.2.1", "{\"username\":\"alice\"}", "198.51.100.1", chain);
        MockHttpServletResponse allowedDifferentClient = invoke(filter, "/api/tokens", "192.0.2.1",
                "{\"username\":\"alice\"}", "198.51.100.2", chain);
        assertThat(allowedDifferentClient.getStatus()).isEqualTo(200);
        assertThat(passed).hasValue(2);
    }

    @Test
    void failsClosedWhenSharedLimiterIsUnavailable() throws Exception {
        LoginAttemptRateLimitFilter filter = new LoginAttemptRateLimitFilter(JSON,
                (account, ip) -> { throw new IllegalStateException("redis down"); }, "");
        AtomicInteger passed = new AtomicInteger();
        MockHttpServletResponse response = invoke(filter, "/api/tokens", "192.0.2.1",
                "{\"username\":\"alice\"}", (request, res) -> passed.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(passed).hasValue(0);
    }

    @Test
    void replaysRequestBodyAndSkipsNonLoginRoutes() throws Exception {
        LoginAttemptRateLimitFilter filter = filter(1, 1, "");
        AtomicInteger passed = new AtomicInteger();
        FilterChain chain = (request, response) -> {
            passed.incrementAndGet();
            assertThat(new String(((jakarta.servlet.http.HttpServletRequest) request).getInputStream()
                    .readAllBytes(), StandardCharsets.UTF_8)).contains("alice");
        };
        MockHttpServletResponse response = invoke(filter, "/api/tokens", "192.0.2.2",
                "{\"username\":\"alice\"}", chain);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(passed).hasValue(1);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/portal/logout");
        MockHttpServletResponse skippedResponse = new MockHttpServletResponse();
        filter.doFilter(request, skippedResponse, (req, res) -> passed.incrementAndGet());
        assertThat(passed).hasValue(2);
    }

    @Test
    void rejectsOversizedLoginPayloadBeforeController() throws Exception {
        LoginAttemptRateLimitFilter filter = filter(1, 1, "");
        AtomicInteger passed = new AtomicInteger();
        MockHttpServletResponse response = invoke(filter, "/api/tokens", "192.0.2.3",
                "x".repeat(9000), (request, res) -> passed.incrementAndGet());
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(passed).hasValue(0);
    }

    @Test
    void concurrentRequestsDoNotExceedGlobalAccountLimit() throws Exception {
        LoginAttemptRateLimitFilter filter = filter(7, 1000, "");
        AtomicInteger passed = new AtomicInteger();
        int requestCount = 16;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(16)) {
            for (int i = 0; i < requestCount; i++) {
                int index = i;
                executor.submit(() -> {
                    ready.countDown();
                    try { start.await(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                    try {
                        invoke(filter, "/api/tokens", "203.0.113." + ((index % 200) + 1),
                                "{\"username\":\"shared-account\"}", (request, response) -> passed.incrementAndGet());
                    } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
            ready.await();
            start.countDown();
        }
        assertThat(passed).hasValue(7);
    }

    private static LoginAttemptRateLimitFilter filter(int accountLimit, int ipLimit, String proxies) {
        AtomicStore fake = new AtomicStore(accountLimit, ipLimit);
        return new LoginAttemptRateLimitFilter(JSON, fake, proxies);
    }

    private static MockHttpServletResponse invoke(LoginAttemptRateLimitFilter filter, String path,
            String remote, String body, FilterChain chain) throws Exception {
        return invoke(filter, path, remote, body, null, chain);
    }

    private static MockHttpServletResponse invoke(LoginAttemptRateLimitFilter filter, String path,
            String remote, String body, String xForwardedFor, FilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(remote);
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        if (xForwardedFor != null) request.addHeader("X-Forwarded-For", xForwardedFor);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    /** Models the single atomic admission operation a Redis EVAL performs. */
    private static final class AtomicStore implements LoginAttemptRateLimiter {
        private final int accountLimit;
        private final int ipLimit;
        private final Map<String, Integer> accounts = new HashMap<>();
        private final Map<String, Integer> ips = new HashMap<>();
        private AtomicStore(int accountLimit, int ipLimit) {
            this.accountLimit = accountLimit;
            this.ipLimit = ipLimit;
        }
        @Override public synchronized long acquire(String account, String ip) {
            int accountCount = accounts.getOrDefault(account, 0);
            int ipCount = ips.getOrDefault(ip, 0);
            if (accountCount >= accountLimit || ipCount >= ipLimit) return 60;
            accounts.put(account, accountCount + 1);
            ips.put(ip, ipCount + 1);
            return 0;
        }
    }
}
