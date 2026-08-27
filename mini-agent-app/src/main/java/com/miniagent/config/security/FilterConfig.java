package com.miniagent.config.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Disable servlet-container auto-registration; filters run only in the Security chain.
 */
@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<SignedSessionFilter> signedSessionRegistration(SignedSessionFilter filter) {
        FilterRegistrationBean<SignedSessionFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitRegistration(RateLimitFilter filter) {
        FilterRegistrationBean<RateLimitFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }
}
