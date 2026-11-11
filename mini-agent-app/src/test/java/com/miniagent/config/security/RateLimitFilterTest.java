package com.miniagent.config.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Regression tests for bounded per-client rate-limit state. */
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        JwtSessionService sessions = mock(JwtSessionService.class);
        when(sessions.resolveUserIdAndRefresh(org.mockito.ArgumentMatchers.any()))
                .thenReturn(null);
        ReflectionTestUtils.setField(filter, "jwtSessionService", sessions);
        ReflectionTestUtils.setField(filter, "perMinute", 60);
        chain = (request, response) -> { };
    }

    @Test
    void idleWindowsAreReapedBeforeARequestAddsANewKey() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, java.util.Deque<Long>> windows =
                (Map<String, java.util.Deque<Long>>) ReflectionTestUtils.getField(filter, "windows");
        @SuppressWarnings("unchecked")
        Map<String, Long> lastAccess =
                (Map<String, Long>) ReflectionTestUtils.getField(filter, "lastAccess");

        long stale = System.currentTimeMillis() - 120_000L;
        windows.put("request:ip:stale", new ArrayDeque<>());
        lastAccess.put("request:ip:stale", stale);

        MockHttpServletRequest request = request("fresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(windows).doesNotContainKey("request:ip:stale");
        assertThat(windows).containsKey("request:ip:fresh");
    }

    @Test
    void activeWindowCacheEvictsOldestEntryInsteadOfRejectingEveryNewClient() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, java.util.Deque<Long>> windows =
                (Map<String, java.util.Deque<Long>>) ReflectionTestUtils.getField(filter, "windows");
        @SuppressWarnings("unchecked")
        Map<String, Long> lastAccess =
                (Map<String, Long>) ReflectionTestUtils.getField(filter, "lastAccess");

        long now = System.currentTimeMillis();
        for (int i = 0; i < 10_000; i++) {
            String key = "request:ip:existing-" + i;
            windows.put(key, new ArrayDeque<>());
            lastAccess.put(key, now);
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request("new-client"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(windows).hasSize(10_000);
        assertThat(windows).containsKey("request:ip:new-client");
    }

    @Test
    void taskStatusPollingDoesNotConsumeTheMutationRateLimit() throws Exception {
        ReflectionTestUtils.setField(filter, "perMinute", 1);
        ReflectionTestUtils.setField(filter, "backgroundReadPerMinute", 5);
        AtomicInteger passed = new AtomicInteger();
        FilterChain countingChain = (request, response) -> passed.incrementAndGet();

        for (int i = 0; i < 5; i++) {
            filter.doFilter(new MockHttpServletRequest(
                    "GET", "/api/conversations/session-1/task"),
                    new MockHttpServletResponse(), countingChain);
        }
        MockHttpServletResponse firstWrite = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(
                "POST", "/api/conversations/session-1/messages/stream"),
                firstWrite, countingChain);
        MockHttpServletResponse secondWrite = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(
                "POST", "/api/conversations/session-1/messages/stream"),
                secondWrite, countingChain);
        MockHttpServletResponse excessPoll = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(
                "GET", "/api/conversations/session-1/task"),
                excessPoll, countingChain);

        assertThat(passed).hasValue(6);
        assertThat(firstWrite.getStatus()).isEqualTo(200);
        assertThat(secondWrite.getStatus()).isEqualTo(429);
        assertThat(secondWrite.getContentAsString()).contains("rate limit exceeded");
        assertThat(secondWrite.getHeader("Retry-After")).isNotNull();
        assertThat(secondWrite.getHeader("X-RateLimit-Source")).isEqualTo("miniagent-api");
        assertThat(excessPoll.getStatus()).isEqualTo(429);
    }

    @Test
    void unrelatedReadRequestsRemainRateLimited() throws Exception {
        ReflectionTestUtils.setField(filter, "perMinute", 1);
        FilterChain countingChain = (request, response) -> { };

        filter.doFilter(new MockHttpServletRequest("GET", "/api/model"),
                new MockHttpServletResponse(), countingChain);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/model"),
                response, countingChain);

        assertThat(response.getStatus()).isEqualTo(429);
    }

    private static MockHttpServletRequest request(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/probe");
        request.setRemoteAddr(ip);
        return request;
    }
}
