package com.miniagent.agent.memory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.memory.manager.ProcedureApplicationSettlementService;
import com.miniagent.memory.port.ProcedureDefinitionPort;
import com.miniagent.memory.port.ProcedureSettlementPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 钉住 remote/local 两种装配形态。
 *
 * <p>最危险的组合是「结算走远端、投影没配」：服务端不认识任何 procedureId，
 * 每次结算都返回 {@code STALE_PROCEDURE}，任务照常成功，成功率永远不动。
 * 这个测试让那种组合在启动期就失败，而不是上线后从日志里发现。</p>
 */
class MemorySettlementConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MemorySettlementConfig.class)
            .withBean(ObjectMapper.class);

    @Test
    void remoteModeExposesBothPortsFromTheSameClient() {
        runner.withPropertyValues(
                        "agent.memory.service.mode=remote",
                        "agent.memory.service.base-url=http://127.0.0.1:18091",
                        "agent.memory.service.internal-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ProcedureSettlementPort settlement = context.getBean(ProcedureSettlementPort.class);
                    ProcedureDefinitionPort definition = context.getBean(ProcedureDefinitionPort.class);
                    assertThat(definition).isSameAs(settlement);
                });
    }

    @Test
    void remoteModeWithoutEndpointFailsFastInsteadOfStarting() {
        runner.withPropertyValues(
                        "agent.memory.service.mode=remote",
                        "agent.memory.service.base-url=",
                        "agent.memory.service.internal-key=test-key")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void remoteModeWithoutInternalKeyFailsFast() {
        runner.withPropertyValues(
                        "agent.memory.service.mode=remote",
                        "agent.memory.service.base-url=http://127.0.0.1:18091")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void localModeKeepsLocalSettlementAndHasNoProjectionPort() {
        ProcedureApplicationSettlementService local = mock(ProcedureApplicationSettlementService.class);
        runner.withBean(ProcedureApplicationSettlementService.class, () -> local)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ProcedureSettlementPort.class)).isSameAs(local);
                    // 本地结算不需要投影：没有远端要投，多一张 outbox 只是空转。
                    assertThat(context.getBeansOfType(ProcedureDefinitionPort.class)).isEmpty();
                });
    }

    @Test
    void missingModeDefaultsToLocal() {
        ProcedureApplicationSettlementService local = mock(ProcedureApplicationSettlementService.class);
        runner.withBean(ProcedureApplicationSettlementService.class, () -> local)
                .run(context -> assertThat(context.getBean(ProcedureSettlementPort.class)).isSameAs(local));
    }
}
