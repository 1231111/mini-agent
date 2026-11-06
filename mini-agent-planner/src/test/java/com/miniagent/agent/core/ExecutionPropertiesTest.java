package com.miniagent.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionPropertiesTest {

    @Test
    void capIterationsUsesCeilingAndKeepsDefaults() {
        ExecutionProperties props = new ExecutionProperties();
        assertEquals(90, props.getMaxIterations());
        assertEquals(25, props.getSubagentMaxIterations());
        assertEquals(90, props.capIterations(120));
        assertEquals(40, props.capIterations(40));
        assertEquals(90, props.capIterations(0));
    }
}
