package com.miniagent.agent.planner;

import com.miniagent.agent.intent.IntentType;
import com.miniagent.agent.intent.TaskPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GoalCompilerTemplateTest {

    @Test
    void templateGraphAtLeastThreeNodesForStructured() {
        PlannerProperties props = new PlannerProperties();
        GoalCompiler compiler = new GoalCompiler(props);
        TaskPlan plan = new TaskPlan(
                IntentType.NEW_TASK, "搭建完整系统", true, true, true,
                null, List.of(), "complex", true);
        TaskGraph g = compiler.templateGraph("一整套微服务平台", plan);
        assertTrue(g.nodes().size() >= 3);
        assertTrue(compiler.validate(g, plan));
        assertFalse(g.hasCycle());
    }
}
