package com.miniagent.agent.delegate;

import com.miniagent.agent.execution.ToolCallContext;
import com.miniagent.agent.tool.CapabilityRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClientMultiAgentToolPolicyTest {

    @Test
    void customToolsCanOnlyReduceConfiguredRoleTools() {
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        when(capabilities.containsTool(anyString())).thenReturn(true);
        ClientMultiAgent agents = new ClientMultiAgent(null, null);
        ReflectionTestUtils.setField(agents, "capabilityRegistry", capabilities);
        RoleConfig role = RoleConfig.builder()
                .allowedTools(List.of("read_file"))
                .build();

        assertEquals(
                List.of("read_file"),
                agents.resolveTools(List.of("read_file", "exec_command"), role));
        assertEquals(List.of(), agents.resolveTools(List.of("exec_command"), role));
    }

    @Test
    void generalSubagentCannotExpandBeyondGeneralCapabilityPack() {
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        when(capabilities.toolsFor(CapabilityRegistry.GENERAL))
                .thenReturn(List.of("read_file", "write_file"));
        when(capabilities.containsTool(anyString())).thenReturn(true);
        ClientMultiAgent agents = new ClientMultiAgent(null, null);
        ReflectionTestUtils.setField(agents, "capabilityRegistry", capabilities);

        assertEquals(
                List.of("read_file"),
                agents.resolveTools(List.of("read_file", "browser_evaluate"), null));
    }

    @Test
    void childToolsAreAlsoLimitedByParentToolSurface() {
        CapabilityRegistry capabilities = mock(CapabilityRegistry.class);
        when(capabilities.containsTool(anyString())).thenReturn(true);
        ClientMultiAgent agents = new ClientMultiAgent(null, null);
        ReflectionTestUtils.setField(agents, "capabilityRegistry", capabilities);
        RoleConfig role = RoleConfig.builder()
                .allowedTools(List.of("read_file", "write_file"))
                .build();

        try (var ignored = ToolCallContext.bind(Set.of("read_file"))) {
            assertEquals(
                    List.of("read_file"),
                    agents.resolveTools(List.of("read_file", "write_file"), role));
        }
    }
}
