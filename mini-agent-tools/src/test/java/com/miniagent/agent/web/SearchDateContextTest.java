package com.miniagent.agent.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

class SearchDateContextTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant AS_OF = Instant.parse("2026-11-12T02:20:34Z");

    @ParameterizedTest
    @ValueSource(strings = {"帮我搜索今日 AI 技术最新动态", "查询今天发布的AI新闻", "今日AI资讯", "Find today's AI news", "AI news today",
            "搜索今日（2026-11-12）的 AI 新闻", "搜索今日11月12日的AI新闻", "查询今天发布的模型", "Find today's AI model releases"})
    void todayMeansTheRequestDayInTheConfiguredZone(String request) {
        var window = SearchDateContext.fromRequest(request, AS_OF, ZONE);
        assertNotNull(window);
        assertEquals(LocalDate.of(2026, 11, 12), window.start());
        assertEquals(window.start(), window.end());
        assertEquals(AS_OF, window.asOf());
        assertEquals(ZONE, window.zone());
    }

    @Test
    void resolvesDayUsingTheRequestZoneRatherThanUtcOrMachineZone() {
        Instant beforeUtcMidnight = Instant.parse("2026-11-11T16:05:00Z");
        assertEquals(LocalDate.of(2026, 11, 12),
                SearchDateContext.fromRequest("今日AI新闻", beforeUtcMidnight, ZONE).start());
        assertEquals(LocalDate.of(2026, 11, 11),
                SearchDateContext.fromRequest("今日AI新闻", beforeUtcMidnight, ZoneId.of("UTC")).start());
    }

    @Test
    void yesterdayRemainsASingleDay() {
        assertEquals(LocalDate.of(2026, 11, 11),
                SearchDateContext.fromRequest("搜索昨日AI新闻", AS_OF, ZONE).start());
    }

    @ParameterizedTest
    @ValueSource(strings = {"搜索最近48小时的AI新闻", "比较今日和昨日的AI新闻", "今日整理11月10日的AI新闻", "今天分析2020年的AI动态",
            "AI新闻 latest", "今天帮我查火车票", "今天搜索历史AI新闻", "today compare AI news from the past two days",
            "今天帮我搜索 OpenAI API 的消息格式", "今天帮我查询如何发布 Spring Boot 应用", "today search AI news from the last 48 hours"})
    void doesNotForceAnAmbiguousOrExplicitlyDifferentPeriodIntoToday(String request) {
        assertNull(SearchDateContext.fromRequest(request, AS_OF, ZONE));
    }

    @Test
    void bindingsRestorePreviousConstraintAndDoNotInheritToOtherThreads() throws Exception {
        assertNull(SearchDateContext.current());
        var today = SearchDateContext.fromRequest("今日AI新闻", AS_OF, ZONE);
        var yesterday = SearchDateContext.fromRequest("昨日AI新闻", AS_OF, ZONE);
        try (var outer = SearchDateContext.bind(today)) {
            assertSame(today, SearchDateContext.current());
            try (var inner = SearchDateContext.bind(yesterday)) {
                assertSame(yesterday, SearchDateContext.current());
            }
            assertSame(today, SearchDateContext.current());
            try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
                assertNull(pool.submit(SearchDateContext::current).get());
            }
        }
        assertNull(SearchDateContext.current());
    }

    @Test
    void invalidConfigurationFallsBackToTheSameDefaultEverywhere() {
        assertEquals(ZONE, SearchDateContext.resolveZone("invalid-zone"));
        assertEquals(ZONE, SearchDateContext.resolveZone(null));
    }
}
