package com.miniagent.agent.memory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.manager.CloudMemoryClient;
import com.miniagent.agent.memory.manager.ProcedureApplicationSettlementService;
import com.miniagent.agent.memory.manager.ProcedureSettlementModeProperties;
import com.miniagent.memory.port.ProcedureSettlementPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.cloud.CloudAccountClient;

/**
 * 本库结算始终在。配置云端地址后，同一个客户端以 Account 用户凭证访问云端记忆服务。
 */
@Configuration
@EnableConfigurationProperties(ProcedureSettlementModeProperties.class)
public class MemorySettlementConfig {

    @Bean
    @ConditionalOnCloudMemory
    public CloudMemoryClient cloudMemoryClient(ProcedureSettlementModeProperties properties,
                                               ObjectMapper objectMapper,
                                               ObjectProvider<StringRedisTemplate> redis,
                                               MeterRegistry metrics,
                                               UserRepository users,
                                               CloudAccountClient accounts) {
        return new CloudMemoryClient(properties, objectMapper, redis, metrics, users, accounts);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "agent.memory.service", name = "mode",
            havingValue = "local", matchIfMissing = true)
    public ProcedureSettlementPort localProcedureSettlementPort(
            ProcedureApplicationSettlementService local) {
        return local;
    }
}
