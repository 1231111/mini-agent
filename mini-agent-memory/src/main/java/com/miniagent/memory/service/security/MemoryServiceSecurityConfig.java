package com.miniagent.memory.service.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("memory-service")
@EnableConfigurationProperties(MemoryServiceSecurityProperties.class)
public class MemoryServiceSecurityConfig {
}
