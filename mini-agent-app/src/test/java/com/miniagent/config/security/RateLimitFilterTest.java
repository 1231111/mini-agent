package com.miniagent.config.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.Map;

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
        windows.put("ip:stale", new ArrayDeque<>());
        lastAccess.put("ip:stale", stale);

        MockHttpServletRequest request = request("fresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(windows).doesNotContainKey("ip:stale");
        assertThat(windows).containsKey("ip:fresh");
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
            String key = "ip:existing-" + i;
            windows.put(key, new ArrayDeque<>());
            lastAccess.put(key, now);
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(request("new-client"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(windows).hasSize(10_000);
        assertThat(windows).containsKey("ip:new-client");
    }

    private static MockHttpServletRequest request(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/probe");
        request.setRemoteAddr(ip);
        return request;
    }
}
