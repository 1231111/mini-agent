package com.miniagent.agent.memory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.manager.CloudMemoryClient;
import com.miniagent.agent.memory.manager.ProcedureApplicationSettlementService;
import com.miniagent.config.repository.UserRepository;
import com.miniagent.config.cloud.CloudAccountClient;
import com.miniagent.memory.port.ProcedureSettlementPort;
import com.miniagent.memory.port.ProjectAuthorizationPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MemorySettlementConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MemorySettlementConfig.class)
            .withBean(ObjectMapper.class)
            .withBean(SimpleMeterRegistry.class)
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(CloudAccountClient.class, () -> mock(CloudAccountClient.class));

    @Test
    void remoteModeExposesCloudClientAsAuthorizationPort() {
        runner.withPropertyValues(
                        "agent.memory.service.mode=remote",
                        "agent.memory.service.base-url=http://127.0.0.1:18091")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CloudMemoryClient client = context.getBean(CloudMemoryClient.class);
                    assertThat(client).isInstanceOf(ProjectAuthorizationPort.class);
                });
    }

    @Test
    void missingEndpointLeavesLocalMemoryWithoutCloudClient() {
        runner.withPropertyValues(
                        "agent.memory.service.mode=remote",
                        "agent.memory.service.base-url=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(CloudMemoryClient.class)).isEmpty();
                });
    }

    @Test
    void cloudClientDoesNotRequireClientSigningKey() {
        runner.withPropertyValues(
                        "agent.memory.service.mode=remote",
                        "agent.memory.service.base-url=http://127.0.0.1:18091")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CloudMemoryClient.class)).isNotNull();
                });
    }

    @Test
    void localModeKeepsLocalSettlementAndHasNoCloudClient() {
        ProcedureApplicationSettlementService local = mock(ProcedureApplicationSettlementService.class);
        runner.withBean(ProcedureApplicationSettlementService.class, () -> local)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ProcedureSettlementPort.class)).isSameAs(local);
                    assertThat(context.getBeansOfType(CloudMemoryClient.class)).isEmpty();
                });
    }

    @Test
    void missingModeDefaultsToLocal() {
        ProcedureApplicationSettlementService local = mock(ProcedureApplicationSettlementService.class);
        runner.withBean(ProcedureApplicationSettlementService.class, () -> local)
                .run(context -> assertThat(context.getBean(ProcedureSettlementPort.class)).isSameAs(local));
    }

    private static String ecPrivateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
    }
}
