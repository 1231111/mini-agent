package com.miniagent.config.security;

import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${agent.auth.allowed-origins:http://localhost:*,http://127.0.0.1:*,file://}")
    private String allowedOrigins;
    @Value("${agent.auth.bcrypt-strength:10}")
    private int bcryptStrength;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(Math.max(10, Math.min(bcryptStrength, 16)));
    }

    /**
     * CORS 配置，支持 Electron 桌面端和 Web 端访问
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList());
        configuration.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"
        ));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        // 暴露 SSE 相关的头
        configuration.setExposedHeaders(Arrays.asList(
                "X-Request-Id",
                "X-CSRF-Error",
                "Cache-Control",
                "Content-Type"
        ));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    /** Keep Spring's strict request-target and header validation enabled. */
    @Bean
    public HttpFirewall httpFirewall() {
        return new StrictHttpFirewall();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   SignedSessionFilter signedSessionFilter,
                                                   RateLimitFilter rateLimitFilter,
                                                   CsrfCookieFilter csrfCookieFilter) throws Exception {
        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                    .frameOptions(frameOptions -> frameOptions.sameOrigin())
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/",
                                "/login",
                                "/error",
                                "/api/login",
                                "/api/register",
                                "/api/auth-status",
                                "/api/logout",
                                "/actuator/health",
                                "/actuator/info",
                                "/css/**",
                                "/js/**",
                                "/favicon.ico"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/static/css/**", "/static/js/**").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            String accept = req.getHeader("Accept");
                            boolean wantsHtml = Objects.nonNull(accept) && accept.contains("text/html");
                            if (wantsHtml && !req.getRequestURI().startsWith("/api")
                                    && !req.getRequestURI().startsWith("/chat")) {
                                res.sendRedirect("/");
                            } else {
                                res.setStatus(401);
                                res.setContentType("application/json;charset=UTF-8");
                                res.getWriter().write("{\"error\":\"Not authenticated\"}");
                            }
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            boolean csrfFailure = e instanceof CsrfException;
                            if (csrfFailure) {
                                boolean cookiePresent = req.getCookies() != null
                                        && Arrays.stream(req.getCookies())
                                        .anyMatch(cookie -> "XSRF-TOKEN".equals(cookie.getName()));
                                boolean headerPresent = req.getHeader("X-XSRF-TOKEN") != null;
                                log.warn("CSRF request rejected method={} path={} cookiePresent={} headerPresent={}",
                                        req.getMethod(), req.getRequestURI(), cookiePresent, headerPresent);
                                res.setHeader("X-CSRF-Error", "true");
                                res.setStatus(403);
                                res.setContentType("application/json;charset=UTF-8");
                                res.getWriter().write("{\"success\":false,\"code\":\"AUTH.02.02\","
                                        + "\"message\":\"CSRF token missing or invalid\"}");
                                return;
                            }
                            log.warn("Access denied method={} path={} reason={}",
                                    req.getMethod(), req.getRequestURI(), e.getClass().getSimpleName());
                            res.setStatus(403);
                            res.setContentType("application/json;charset=UTF-8");
                            res.getWriter().write("{\"success\":false,\"code\":\"AUTH.02.02\","
                                    + "\"message\":\"Forbidden\"}");
                        })
                )
                .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(signedSessionFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(csrfCookieFilter, CsrfFilter.class);
        return http.build();
    }
}
