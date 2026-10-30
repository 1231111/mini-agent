package com.miniagent.memory.service.evolution;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("memory-service")
@EnableConfigurationProperties(EvolutionProperties.class)
public class EvolutionServiceConfiguration {
}
