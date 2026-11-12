package com.miniagent.agent.context;

import com.miniagent.agent.web.SearchDateContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void todayLatestNewsIsBoundedByLocalMidnightAndAsOfTime() {
        String guidance = RelativeDateGuidance.render(
                ZonedDateTime.parse("2026-11-12T10:20:34+08:00[Asia/Shanghai]"));

        assertTrue(guidance.contains("2026-11-12T00:00:00+08:00"));
        assertTrue(guidance.contains("2026-11-12T10:20:34+08:00"));
        assertTrue(guidance.contains("dateFrom=2026-11-12"));
        assertTrue(guidance.contains("dateTo=2026-11-12"));
        assertTrue(guidance.contains("禁止擅自扩大为最近24/48小时"));
        assertTrue(guidance.contains("最新/latest 只在用户指定日期范围内取最新"));
        assertTrue(guidance.contains("不足时如实说明"));
    }

    @Test
    void todayUsesUsersLocalDateEvenWhenUtcIsStillYesterday() {
        ZonedDateTime instantInUtc = ZonedDateTime.parse("2026-11-11T16:05:00Z");
        String guidance = RelativeDateGuidance.render(
                instantInUtc.withZoneSameInstant(ZoneId.of("Asia/Shanghai")));

        assertTrue(guidance.contains("当前日期是 2026-11-12"));
        assertTrue(guidance.contains("dateFrom=2026-11-12"));
        assertTrue(guidance.contains("dateTo=2026-11-12"));
        assertTrue(guidance.contains("2026-11-12T00:05:00+08:00"));
        assertFalse(guidance.contains("dateFrom=2026-11-11"));
    }

    @Test
    void eachRenderComputesANewDateAcrossMidnightAndYearBoundary() {
        String before = RelativeDateGuidance.render(
                ZonedDateTime.parse("2026-12-31T23:59:59+08:00[Asia/Shanghai]"));
        String after = RelativeDateGuidance.render(
                ZonedDateTime.parse("2027-01-01T00:00:01+08:00[Asia/Shanghai]"));

        assertTrue(before.contains("dateFrom=2026-12-31"));
        assertTrue(after.contains("当前日期是 2027-01-01"));
        assertTrue(after.contains("dateFrom=2027-01-01"));
        assertTrue(after.contains("dateTo=2027-01-01"));
        assertTrue(after.contains("2027-01-01T00:00:00+08:00"));
        assertFalse(after.contains("2026-12-31"));
    }

    @Test
    void boundRequestTimeAndZoneStayFixedAcrossFurtherModelLoops() {
        SearchDateContext.Window window = new SearchDateContext.Window(
                LocalDate.parse("2026-11-12"), LocalDate.parse("2026-11-12"),
                ZoneId.of("Asia/Shanghai"), Instant.parse("2026-11-12T15:59:59Z"));
        try (SearchDateContext.Scope ignored = SearchDateContext.bind(window)) {
            String guidance = RelativeDateGuidance.render(RelativeDateGuidance.resolveNow("Pacific/Honolulu"));

            assertTrue(guidance.contains("当前日期是 2026-11-12"));
            assertTrue(guidance.contains("时区是 Asia/Shanghai"));
            assertTrue(guidance.contains("2026-11-12T23:59:59+08:00"));
            assertTrue(guidance.contains("dateFrom=2026-11-12"));
            assertTrue(guidance.contains("dateTo=2026-11-12"));
        }
    }
}
