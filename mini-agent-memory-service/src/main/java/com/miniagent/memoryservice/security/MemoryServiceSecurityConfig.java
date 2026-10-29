package com.miniagent.memoryservice.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MemoryServiceSecurityProperties.class)
public class MemoryServiceSecurityConfig {
}
