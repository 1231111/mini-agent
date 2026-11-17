package com.miniagent.agent.planner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for a submit-only deliver node consuming a validated predecessor. */
class NodeOutputBinderPredecessorTest {

    @Test
    void submitOnlyDeliveryBindsDeclaredOutputFromPredecessorEvidence() {
        TaskNode deliver = new TaskNode(
                "n3", "交付可打开的页面链接", "deliver", List.of("n2"),
                List.of("浏览器验证记录"), List.of("page_link"), TaskNodeStatus.READY, 0,
                new DoneWhen(DoneWhen.NOTE, "交付可打开的页面链接", ""), "", "", 0, "");

        String validated = "页面验证通过；URL=http://127.0.0.1:53112/index.html；无 page error / console error";
        NodeOutputBinder.Bound bound = NodeOutputBinder.bind(
                deliver, "", List.of(), "", null, validated);

        assertTrue(bound.complete(deliver));
        assertEquals(validated, bound.evidence());
        assertEquals("http://127.0.0.1:53112/index.html", bound.bindings().get("page_link"));
    }
}
