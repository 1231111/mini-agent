package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StepEvaluatorTest {

    @Test
    void hollowEvidenceDetectedForFileDeliveryNode() {
        TaskNode node = new TaskNode(
                "n1", "写 eval_cpl06_report.md", "file_write",
                null, null, null, TaskNodeStatus.READY, 0,
                DoneWhen.file("eval_cpl06_report.md"), "", "", 0, "");
        assertTrue(StepEvaluator.isHollowEvidence(AgentLoop.STEP_SEGMENT_DONE));
        assertTrue(StepEvaluator.looksLikeFileDelivery(node));
    }
}
