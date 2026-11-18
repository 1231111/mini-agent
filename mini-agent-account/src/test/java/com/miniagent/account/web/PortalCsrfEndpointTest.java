package com.miniagent.account.web;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class PortalCsrfEndpointTest {
    @Test
    void createsUncachedHostOnlySecureTokenCookieInProduction() {
        PortalController controller = new PortalController();
        ReflectionTestUtils.setField(controller, "secureCookie", true);
        HttpServletResponse response = new MockHttpServletResponse();

        var body = controller.csrf(response);

        assertThat(body.isSuccess()).isTrue();
        assertThat(body.getData().get("token")).hasSize(43);
        assertThat(((MockHttpServletResponse) response).getHeader("Set-Cookie"))
                .contains("__Host-PORTAL-XSRF=")
                .contains("Secure")
                .contains("SameSite=Strict")
                .doesNotContain("HttpOnly");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
}
