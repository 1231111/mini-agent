package com.miniagent.memoryservice.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.memory.contract.MemoryAccessToken;
import com.miniagent.memory.contract.ServiceToken;
import com.miniagent.memoryservice.config.AccountIntrospectionClient;
import com.miniagent.memoryservice.config.MemoryServiceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceTokenFilterAuthenticationTest {
    private static final byte[] KEY = new byte[32];
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void accountExplicitRejectionDoesNotFallBackOffline() throws Exception {
        String token = token();
        AccountIntrospectionClient account = mock(AccountIntrospectionClient.class);
        when(account.introspect(token)).thenThrow(
                new AccountIntrospectionClient.InvalidTokenException("revoked"));
        ServiceTokenFilter filter = filter(account);
        MockHttpServletRequest request = request(token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        verify(account).introspect(token);
    }

    @Test
    void accountUnexpectedFailureFailsClosedWithoutOfflineFallback() throws Exception {
        String token = token();
        AccountIntrospectionClient account = mock(AccountIntrospectionClient.class);
        when(account.introspect(token)).thenThrow(new IllegalStateException("bad response"));
        ServiceTokenFilter filter = filter(account);
        MockHttpServletRequest request = request(token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(503, response.getStatus());
        verify(account).introspect(token);
    }

    private ServiceTokenFilter filter(AccountIntrospectionClient account) {
        MemoryServiceProperties properties = new MemoryServiceProperties("api", Map.of(),
                "http://account", "internal", Base64.getEncoder().encodeToString(KEY), 100, 100);
        return new ServiceTokenFilter(properties, json, account, Clock.systemUTC());
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST",
                "/internal/v2/memory/context:assemble");
        request.addHeader(ServiceToken.HEADER, ServiceToken.BEARER + token);
        return request;
    }

    private String token() {
        return MemoryAccessToken.issue(json, KEY, 10, 20, "user", "User", "USER", Clock.systemUTC().instant());
    }
}
