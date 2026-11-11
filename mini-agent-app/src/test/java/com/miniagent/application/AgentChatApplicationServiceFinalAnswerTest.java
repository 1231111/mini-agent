package com.miniagent.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentChatApplicationServiceFinalAnswerTest {

    @Test
    void blankFinalAnswerGetsVisibleFallback() {
        assertEquals(
                "任务已结束，但智能体没有生成最终回复。请查看右侧任务详情中的执行记录和已生成文件。",
                AgentChatApplicationService.ensureFinalAnswer("  "));
    }

    @Test
    void nonBlankFinalAnswerIsPreserved() {
        assertEquals("页面已经生成。", AgentChatApplicationService.ensureFinalAnswer("页面已经生成。"));
    }
}
