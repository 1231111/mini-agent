package com.miniagent.agent.memory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.manager.ProcedureApplicationSettlementService;
import com.miniagent.agent.memory.manager.ProcedureSettlementModeProperties;
import com.miniagent.agent.memory.manager.RemoteProcedureSettlementPort;
import com.miniagent.memory.port.ProcedureDefinitionPort;
import com.miniagent.memory.port.ProcedureSettlementPort;
import com.miniagent.memory.port.ProjectAuthorizationPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 选择本地数据库结算或独立记忆服务结算。
 *
 * <p>remote 模式下投影端口也必须存在：没有投影，服务端不认识任何 {@code procedureId}，
 * 结算会全部落到 {@code STALE_PROCEDURE}。两者由同一个客户端提供，避免出现
 * 「结算走远端、投影没配」这种一半接通的组合。</p>
 */
@Configuration
@EnableConfigurationProperties(ProcedureSettlementModeProperties.class)
public class MemorySettlementConfig {

    @Bean
    @ConditionalOnProperty(prefix = "agent.memory.service", name = "mode", havingValue = "remote")
    public RemoteProcedureSettlementPort remoteMemoryClient(
            ObjectMapper objectMapper, ProcedureSettlementModeProperties properties) {
        return new RemoteProcedureSettlementPort(objectMapper, properties);
    }

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "agent.memory.service", name = "mode", havingValue = "remote")
    public ProcedureSettlementPort remoteProcedureSettlementPort(RemoteProcedureSettlementPort client) {
        return client;
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.memory.service", name = "mode", havingValue = "remote")
    public ProcedureDefinitionPort remoteProcedureDefinitionPort(RemoteProcedureSettlementPort client) {
        return client;
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.memory.service", name = "mode", havingValue = "remote")
    public ProjectAuthorizationPort remoteProjectAuthorizationPort(RemoteProcedureSettlementPort client) {
        return client;
    }

    @Bean
    @Primary
    @ConditionalOnProperty(prefix = "agent.memory.service", name = "mode", havingValue = "local", matchIfMissing = true)
    public ProcedureSettlementPort localProcedureSettlementPort(
            ProcedureApplicationSettlementService local) {
        return local;
    }
}
