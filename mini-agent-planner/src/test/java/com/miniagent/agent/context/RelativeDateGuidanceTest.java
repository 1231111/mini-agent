package com.miniagent.agent.context;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RelativeDateGuidanceTest {

    @Test
    void rendersTodayAndTimezoneForRelativeDateRequests() {
        String guidance = RelativeDateGuidance.render(
                ZonedDateTime.parse("2026-11-10T09:30:00+08:00[Asia/Shanghai]"));

        assertTrue(guidance.contains("当前日期是 2026-11-10"));
        assertTrue(guidance.contains("时区是 Asia/Shanghai"));
        assertTrue(guidance.contains("搜索新闻或资料时用换算后的日期限定检索"));
        assertTrue(guidance.contains("核对来源页面标注的发布日期"));
    }
}
