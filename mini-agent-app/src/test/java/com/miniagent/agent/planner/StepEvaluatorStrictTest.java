package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StepEvaluatorStrictTest {

    @Test
    void rejectsLoosePassAndShortNote() {
        PlannerProperties props = new PlannerProperties();
        props.setStrictEval(true);
        StepEvaluator ev = new StepEvaluator(props, new PlannerMetrics(), null);
        TaskNode node = new TaskNode("n1", "写文档", "file_write", java.util.List.of(),
                TaskNodeStatus.RUNNING, 1, "note_required", "write_file", "", 0);
        assertFalse(ev.evaluate(node, "ok", "短").ok());
        assertTrue(ev.evaluate(node, "", "足够长的验收说明文字").ok());

        TaskNode fileNode = new TaskNode("n2", "落盘", "file_write", java.util.List.of(),
                TaskNodeStatus.RUNNING, 1, "file_exists:workspace/no-such-xyz.md",
                "write_file", "", 0);
        assertFalse(ev.evaluate(fileNode, "done", "workspace/no-such-xyz.md").ok());
    }

    @Test
    void todoCompletedStillChecksFileExistsWhenStrict() {
        PlannerProperties props = new PlannerProperties();
        props.setStrictEval(true);
        StepEvaluator ev = new StepEvaluator(props, new PlannerMetrics(), null);
        TaskNode fileNode = new TaskNode("n2", "落盘", "file_write", java.util.List.of(),
                TaskNodeStatus.RUNNING, 1, "file_exists:workspace/no-such-xyz.md",
                "write_file", "", 0);
        assertFalse(ev.evaluateAfterLoop(fileNode, true, "workspace/no-such-xyz.md").ok());
    }
}
