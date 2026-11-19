package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEnvironmentToolTest {

    @Test
    void environmentReceiptOmitsPersonalHostIdentity() throws Exception {
        ToolRegistry registry = new ToolRegistry();
        AgentEnvironmentTool environment = new AgentEnvironmentTool();
        Field registryField = AgentEnvironmentTool.class.getDeclaredField("toolRegistry");
        registryField.setAccessible(true);
        registryField.set(environment, registry);
        environment.register();

        Tool tool = registry.getAllTools().stream()
                .filter(candidate -> "agent_environment".equals(candidate.getName()))
                .findFirst().orElseThrow();
        JsonNode result = new ObjectMapper().readTree(tool.execute("{}"));

        assertTrue(result.has("project_root"));
        assertFalse(result.has("username"));
        assertFalse(result.has("user_home"));
        assertFalse(result.has("user_dir"));
    }
}
