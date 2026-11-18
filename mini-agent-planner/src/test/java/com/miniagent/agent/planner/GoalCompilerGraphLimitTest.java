package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class GoalCompilerGraphLimitTest {

    @Test
    void rejectsOversizedModelGraphBeforeBuildingTaskNodes() {
        PlannerProperties properties = new PlannerProperties();
        properties.setMaxGraphNodes(1);
        GoalCompiler compiler = new GoalCompiler(properties);

        assertThrows(IllegalArgumentException.class,
                () -> compiler.parseGraph("{\"nodes\":[{},{}]}"));
    }
}
