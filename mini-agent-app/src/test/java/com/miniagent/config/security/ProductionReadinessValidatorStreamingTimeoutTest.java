package com.miniagent.config.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionReadinessValidatorStreamingTimeoutTest {

    @Test
    void defaultConfigurationKeepsSseAndMvcAlivePastAgentDeadline() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("AGENT_SSE_TIMEOUT_MS", "2100000");
        MutablePropertySources sources = environment.getPropertySources();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }

        List<String> errors = new ArrayList<>();
        ProductionReadinessValidator.validateStreamingTimeouts(environment, errors);

        assertThat(environment.getProperty("agent.execution.deadline-ms", Long.class)).isEqualTo(1_800_000L);
        assertThat(environment.getProperty("agent.sse.timeout-ms", Long.class)).isEqualTo(2_100_000L);
        assertThat(environment.getProperty("spring.mvc.async.request-timeout", Long.class)).isEqualTo(2_100_000L);
        assertThat(errors).isEmpty();
    }

    @Test
    void rejectsFiniteSseOrMvcTimeoutWithoutTerminalEventGrace() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.execution.deadline-ms", "1800000")
                .withProperty("agent.sse.timeout-ms", "1800000")
                .withProperty("spring.mvc.async.request-timeout", "1859999");
        List<String> errors = new ArrayList<>();

        ProductionReadinessValidator.validateStreamingTimeouts(environment, errors);

        assertThat(errors).containsExactly(
                "agent.sse.timeout-ms must be unlimited or at least 60000 ms longer than agent.execution.deadline-ms",
                "spring.mvc.async.request-timeout must be unlimited or at least 60000 ms longer than agent.execution.deadline-ms");
    }

    @Test
    void permitsExplicitlyUnlimitedTransportTimeouts() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.execution.deadline-ms", "1800000")
                .withProperty("agent.sse.timeout-ms", "0")
                .withProperty("spring.mvc.async.request-timeout", "-1");
        List<String> errors = new ArrayList<>();

        ProductionReadinessValidator.validateStreamingTimeouts(environment, errors);

        assertThat(errors).isEmpty();
    }
}
