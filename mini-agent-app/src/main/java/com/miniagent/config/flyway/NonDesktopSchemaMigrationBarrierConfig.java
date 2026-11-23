package com.miniagent.config.flyway;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Provides the shared schema-migration lifecycle bean outside the desktop profile.
 * Production uses Spring Boot's normal database-initialization ordering; this
 * no-op bean exists so desktop-only ordering references never break prod startup.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!desktop")
public class NonDesktopSchemaMigrationBarrierConfig {

    @Bean(name = "postJpaSchemaMigrationBarrier")
    InitializingBean postJpaSchemaMigrationBarrier() {
        return () -> { };
    }
}
