package com.miniagent.agent.execution;

import com.miniagent.agent.tool.ToolConcurrencyPolicy;
import com.miniagent.agent.tool.ToolDescriptor;
import com.miniagent.agent.tool.ToolSideEffect;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionGuardsMcpIsolationTest {

    @Test
    void mcpLockIsPerConfiguredServerAndCannotBlockLocalGlobalWrites() {
        ToolDescriptor mcpA = descriptor("mcp__server_a__update",
                ToolConcurrencyPolicy.mcpExternalWriteProfile("server-a", 45));
        ToolDescriptor mcpB = descriptor("mcp__server_b__update",
                ToolConcurrencyPolicy.mcpExternalWriteProfile("server-b", 120));
        ToolDescriptor todo = descriptor("todo", ToolConcurrencyPolicy.profileOf("todo"));

        String aKey = ToolExecutionGuards.resourceKey(mcpA, "{}", "session-a");
        String bKey = ToolExecutionGuards.resourceKey(mcpB, "{}", "session-b");
        String todoKey = ToolExecutionGuards.resourceKey(todo, "{}", "session-a");

        assertThat(mcpA.concurrencyScope()).isEqualTo(com.miniagent.agent.tool.ToolConcurrencyScope.SHARED_RESOURCE);
        assertThat(aKey).isEqualTo("mcp:server-a");
        assertThat(bKey).isEqualTo("mcp:server-b");
        assertThat(todoKey).isEqualTo("global");
        assertThat(aKey).isNotEqualTo(bKey).isNotEqualTo(todoKey);
    }

    @Test
    void configuredMcpOuterDeadlineIsStrictlyLongerThanTheInternalRequestDeadline() {
        ToolDescriptor descriptor = descriptor("mcp__server_a__update",
                ToolConcurrencyPolicy.mcpExternalWriteProfile("server-a", 600));

        assertThat(descriptor.executionBudgetSeconds()).isEqualTo(600);
        assertThat(descriptor.timeoutSeconds()).isGreaterThan(descriptor.executionBudgetSeconds());
        assertThat(descriptor.timeoutSeconds() - descriptor.executionBudgetSeconds()).isEqualTo(15);
    }

    private static ToolDescriptor descriptor(String name,
                                             com.miniagent.agent.tool.ToolExecutionProfile profile) {
        return new ToolDescriptor(name, "test", Map.of(), ToolSideEffect.EXTERNAL_WRITE,
                false, false, false, profile);
    }
}
