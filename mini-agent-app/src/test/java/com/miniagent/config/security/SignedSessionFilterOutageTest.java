package com.miniagent.config.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SignedSessionFilterOutageTest {

    @Test
    void redisSessionStoreOutageReturnsRetryable503AndDoesNotReachController() throws Exception {
        JwtSessionService sessions = mock(JwtSessionService.class);
        when(sessions.resolveUserIdAndRefresh(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));
        SignedSessionFilter filter = new SignedSessionFilter();
        ReflectionTestUtils.setField(filter, "jwtSessionService", sessions);
        ReflectionTestUtils.setField(filter, "userRepository", mock(com.miniagent.config.repository.UserRepository.class));
        ReflectionTestUtils.setField(filter, "tenantRepository", mock(com.miniagent.config.repository.TenantRepository.class));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/private");
        request.addHeader("Authorization", "Bearer valid-shaped-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("5");
        assertThat(response.getContentAsString()).contains("session store unavailable");
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
