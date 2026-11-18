package com.miniagent.account.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AccountPortalCsrfFilterTest {
    private static final String TOKEN = "A".repeat(43);

    @Test
    void rejectsCookieAuthenticatedPortalPostsWithoutMatchingToken() throws Exception {
        AccountPortalCsrfFilter filter = filter(false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/portal/orders");
        request.setCookies(new Cookie(PortalController.CSRF_COOKIE, TOKEN));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (req, res) -> {
            throw new AssertionError("request must be rejected before the controller");
        });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("AUTH.02.02");
    }

    @Test
    void acceptsOnlyMatchingDoubleSubmitTokenAndUsesSecureHostCookieInProduction() throws Exception {
        AccountPortalCsrfFilter filter = filter(true);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/portal/session");
        request.setCookies(new Cookie(PortalController.SECURE_CSRF_COOKIE, TOKEN));
        request.addHeader("X-CSRF-TOKEN", TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger passed = new AtomicInteger();
        FilterChain chain = (req, res) -> passed.incrementAndGet();

        filter.doFilterInternal(request, response, chain);

        assertThat(passed).hasValue(1);
        assertThat(AccountPortalCsrfFilter.validToken(TOKEN, TOKEN)).isTrue();
        assertThat(AccountPortalCsrfFilter.validToken(TOKEN, "B".repeat(43))).isFalse();
        assertThat(AccountPortalCsrfFilter.validToken(TOKEN, "short")).isFalse();
    }

    @Test
    void refusesAmbiguousDuplicateCsrfCookies() throws Exception {
        AccountPortalCsrfFilter filter = filter(false);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/portal/logout");
        request.setCookies(new Cookie(PortalController.CSRF_COOKIE, TOKEN),
                new Cookie(PortalController.CSRF_COOKIE, TOKEN));
        request.addHeader("X-CSRF-TOKEN", TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, (req, res) -> {
            throw new AssertionError("ambiguous cookies must be rejected");
        });

        assertThat(response.getStatus()).isEqualTo(403);
    }

    private static AccountPortalCsrfFilter filter(boolean secure) {
        AccountPortalCsrfFilter filter = new AccountPortalCsrfFilter(new ObjectMapper());
        ReflectionTestUtils.setField(filter, "secureCookie", secure);
        return filter;
    }
}
