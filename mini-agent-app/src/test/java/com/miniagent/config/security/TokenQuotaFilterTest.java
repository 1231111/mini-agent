package com.miniagent.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.config.cloud.TokenEntitlementGate;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 额度过滤器的收口范围与响应形态。
 *
 * <p>两个断言最容易被改坏：</p>
 * <ol>
 *   <li><b>只拦对话发起。</b> 放得太宽会把读历史、建会话一起拦掉，用户会觉得整站挂了。</li>
 *   <li><b>超额返回 429 + message。</b> 前端在 {@code !resp.ok} 时读的是 body.message，
 *       返回空 body 会让用户只看到「请求失败」，不知道是额度用完了。</li>
 * </ol>
 */
class TokenQuotaFilterTest {

    private static final Long USER = 7L;
    private static final String MESSAGES = "/api/conversations/s_abc/messages";
    private static final String STREAM = "/api/conversations/s_abc/messages/stream";

    private static MockHttpServletRequest post(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.setAttribute(JwtSessionService.ATTR_USER_ID, USER);
        return request;
    }

    private static TokenQuotaFilter filter(String rejection) {
        TokenEntitlementGate gate = mock(TokenEntitlementGate.class);
        when(gate.rejectionReason(anyLong())).thenReturn(rejection);
        return new TokenQuotaFilter(gate, new ObjectMapper());
    }

    @Test
    void gatesBothMessageEndpoints() {
        TokenQuotaFilter filter = filter(null);
        assertFalse(filter.shouldNotFilter(post(MESSAGES)));
        assertFalse(filter.shouldNotFilter(post(STREAM)));
        assertFalse(filter.shouldNotFilter(post("/api/conversations/s_abc/todos/1/continue")));
    }

    @Test
    void ignoresReadsAndOtherPaths() {
        TokenQuotaFilter filter = filter(null);
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", MESSAGES)));

        MockHttpServletRequest create = new MockHttpServletRequest("POST", "/api/conversations");
        create.setServletPath("/api/conversations");
        assertTrue(filter.shouldNotFilter(create));

        MockHttpServletRequest files = new MockHttpServletRequest("POST", "/api/conversations/s_abc/files");
        files.setServletPath("/api/conversations/s_abc/files");
        assertTrue(filter.shouldNotFilter(files));
    }

    @Test
    void overQuotaReturns429WithMessage() throws Exception {
        TokenQuotaFilter filter = filter("今日 token 额度已用完（250000 / 200000）");
        MockHttpServletRequest request = post(STREAM);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(429, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("额度已用完"), body);
        assertTrue(body.contains("TOKEN_QUOTA_EXCEEDED"), body);
        assertTrue(response.getContentType().startsWith("application/json"));
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void allowedPassesThrough() throws Exception {
        TokenQuotaFilter filter = filter(null);
        MockHttpServletRequest request = post(MESSAGES);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertEquals("", response.getContentAsString());
        verify(chain).doFilter(request, response);
    }

    @Test
    void missingIdentityDefersToSecurityChain() throws Exception {
        TokenQuotaFilter filter = filter("超额");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", MESSAGES);
        request.setServletPath(MESSAGES);   // 不放 ATTR_USER_ID
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        // 没认证信息时不在这里判 401，交给后面的安全链，避免两处给出不一致的错误。
        verify(chain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }
}
