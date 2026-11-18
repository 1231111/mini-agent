package com.miniagent.agent.mcp;

import com.miniagent.agent.tool.ToolConcurrencyPolicy;
import com.miniagent.agent.tool.ToolConcurrencyScope;
import com.miniagent.agent.tool.ToolTimeoutRecovery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolExecutionContractTest {
    @Test
    void mcpToolsUseExplicitExternalWriteContract() {
        var profile = ToolConcurrencyPolicy.profileOf("mcp__filesystem__read_file");

        assertThat(profile.executionBudgetSeconds()).isEqualTo(60);
        assertThat(profile.concurrencyScope()).isEqualTo(ToolConcurrencyScope.GLOBAL);
        assertThat(profile.timeoutRecovery()).isEqualTo(ToolTimeoutRecovery.ABORT);
        assertThat(profile.maxRetries()).isZero();
        assertThat(profile.recoveryHint()).contains("禁止直接重试");
    }
}
