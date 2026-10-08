package com.miniagent.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SecurityConfigAuthorizationTest.ProbeController.class)
@Import({
        SecurityConfig.class,
        SecurityConfigAuthorizationTest.PassThroughFilters.class,
        SecurityConfigAuthorizationTest.ProbeController.class
})
class SecurityConfigAuthorizationTest {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private JwtSessionService jwtSessionService;
    @MockitoBean
    private com.miniagent.config.repository.UserRepository userRepository;
    @MockitoBean
    private com.miniagent.config.repository.TenantRepository tenantRepository;

    @Test
    @WithMockUser(roles = "USER")
    void regularUserCannotAccessAdminApi() throws Exception {
        mvc.perform(get("/api/admin/probe")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    void systemAdminCanAccessAdminAndMcpMutation() throws Exception {
        mvc.perform(get("/api/admin/probe")).andExpect(status().isOk());
        mvc.perform(post("/api/mcp/servers/probe")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "USER")
    void regularUserCanReadButCannotMutateMcpServers() throws Exception {
        mvc.perform(get("/api/mcp/servers/probe")).andExpect(status().isOk());
        mvc.perform(post("/api/mcp/servers/probe")).andExpect(status().isForbidden());
    }

    /**
     * CSP 是纵深防御：它拦不住行内 onerror=（页面有 4k 行内联 JS，必须保留 'unsafe-inline'），
     * 但能拦住"加载外域脚本"、"object/embed 注入" 与 "<base> 劫持相对 URL"。
     * 真正修 XSS 的是渲染层先转义（chat.html renderMD），这里锁住头部不被误删。
     */
    @Test
    @WithMockUser(roles = "USER")
    void securityHeadersAreApplied() throws Exception {
        // 用普通用户可读的探针端点（admin 端点对 USER 是 403，校验头部就没有意义了）
        mvc.perform(get("/api/mcp/servers/probe"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy",
                        allOf(
                                containsString("default-src 'self'"),
                                containsString("object-src 'none'"),
                                containsString("base-uri 'none'"))))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @RestController
    static class ProbeController {
        @GetMapping({"/api/admin/probe", "/api/mcp/servers/probe"})
        String read() {
            return "ok";
        }

        @PostMapping("/api/mcp/servers/probe")
        String write() {
            return "ok";
        }
    }

    @TestConfiguration
    static class PassThroughFilters {
        @Bean
        SignedSessionFilter signedSessionFilter() {
            return new SignedSessionFilter() {
                @Override
                protected void doFilterInternal(
                        HttpServletRequest request,
                        HttpServletResponse response,
                        FilterChain chain) throws ServletException, IOException {
                    chain.doFilter(request, response);
                }
            };
        }

        @Bean
        RateLimitFilter rateLimitFilter() {
            return new RateLimitFilter() {
                @Override
                protected void doFilterInternal(
                        HttpServletRequest request,
                        HttpServletResponse response,
                        FilterChain chain) throws ServletException, IOException {
                    chain.doFilter(request, response);
                }
            };
        }
    }
}
