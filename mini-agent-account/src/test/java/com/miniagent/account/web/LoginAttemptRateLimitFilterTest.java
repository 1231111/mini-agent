package com.miniagent.account.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptRateLimitFilterTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void capsAttemptsForSameNormalizedAccountAndIpAndReturnsRetryAfter() throws Exception {
        LoginAttemptRateLimitFilter filter = filter(2, 10, "");
        AtomicInteger passed = new AtomicInteger();
        FilterChain chain = (request, response) -> passed.incrementAndGet();

        invoke(filter, "/api/tokens", "192.0.2.10", "{\"username\":\"Ａlice\",\"password\":\"x\"}", chain);
        invoke(filter, "/api/portal/session", "192.0.2.10", "{\"username\":\"alice\",\"password\":\"x\"}", chain);
        MockHttpServletResponse rejected = invoke(filter, "/api/tokens", "192.0.2.10",
                "{\"username\":\"ALICE\",\"password\":\"x\"}", chain);

        assertThat(passed).hasValue(2);
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("60");
        assertThat(rejected.getContentAsString()).contains("登录尝试过于频繁");
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

    private static LoginAttemptRateLimitFilter filter(int accountLimit, int ipLimit, String proxies) {
        return new LoginAttemptRateLimitFilter(JSON,
                Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC),
                accountLimit, ipLimit, 60, proxies);
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
}
