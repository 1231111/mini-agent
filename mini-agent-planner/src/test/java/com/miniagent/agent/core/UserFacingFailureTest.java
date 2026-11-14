package com.miniagent.agent.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserFacingFailureTest {
    @Test void preservesUsefulBusinessReason() {
        assertEquals("日期已过，请重新选择出行日期", UserFacingFailure.summarize(
                "日期已过，请重新选择出行日期", "任务未完成"));
    }
    @Test void keepsPlaywrightDumpOutOfAnswer() {
        String shown = UserFacingFailure.summarize("点击结果尚未确认：Error {\nmessage='Timeout 2000ms exceeded.\n"
                + "stack='TimeoutError\n at ProgressController.run (C:\\Users\\abc\\private.js:92:28)", "操作回执未确认。");
        assertTrue(shown.startsWith("操作回执未确认。"));
        assertTrue(shown.contains("诊断编号："));
        assertFalse(shown.contains("C:\\Users"));
        assertFalse(shown.contains("ProgressController"));
    }
    @Test void handlesJavaAndJavascriptStacksAndEmptyResults() {
        for (String detail : new String[]{"IOException\n\tat com.example.Secret.run(Secret.java:12)",
                "Error: fail\n    at run (/tmp/private.js:5:6)"}) {
            assertTrue(UserFacingFailure.summarize(detail, "未完成").startsWith("未完成 诊断编号："));
        }
        assertEquals("未完成", UserFacingFailure.summarize(null, "未完成"));
    }
}
