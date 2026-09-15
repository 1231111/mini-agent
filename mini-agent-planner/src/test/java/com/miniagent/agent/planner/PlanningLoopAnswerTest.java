package com.miniagent.agent.planner;

import com.miniagent.agent.core.AgentLoop;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanningLoopAnswerTest {

    @Test
    void stubDoesNotReplaceRealAnswer() {
        String kept = PlanningLoop.keepBetterAnswer(
                "文件路径：workspace/qa.md", AgentLoop.STEP_SEGMENT_DONE);
        assertEquals("文件路径：workspace/qa.md", kept);
    }

    @Test
    void realAnswerReplacesPrevious() {
        String kept = PlanningLoop.keepBetterAnswer("旧", "OpenJDK");
        assertEquals("OpenJDK", kept);
    }
}
