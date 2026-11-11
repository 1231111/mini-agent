package com.miniagent.agent.web;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebSearchServiceDateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);

    @Test
    void resolvesTodayAndYesterdayAgainstTheConfiguredLocalDate() {
        assertEquals(new WebSearchService.DateWindow(TODAY, TODAY),
                WebSearchService.resolveDateWindow("今日 AI 技术最新动态", null, null, TODAY));
        LocalDate yesterday = TODAY.minusDays(1);
        assertEquals(new WebSearchService.DateWindow(yesterday, yesterday),
                WebSearchService.resolveDateWindow("yesterday AI news", null, null, TODAY));
    }

    @Test
    void explicitToolDatesTakePrecedenceAndQueryDatesAreRecognized() {
        assertEquals(new WebSearchService.DateWindow(LocalDate.of(2026, 11, 10), TODAY),
                WebSearchService.resolveDateWindow("AI 技术新闻", "2026-11-10", "2026-11-11", TODAY));
        assertEquals(new WebSearchService.DateWindow(TODAY, TODAY),
                WebSearchService.resolveDateWindow("AI 新闻 2026-11-11", null, null, TODAY));
    }

    @Test
    void rejectsInvalidOrReversedDateWindows() {
        assertThrows(IllegalArgumentException.class,
                () -> WebSearchService.resolveDateWindow("AI 新闻", "2026-11-12", "2026-11-11", TODAY));
        assertThrows(IllegalArgumentException.class,
                () -> WebSearchService.resolveDateWindow("AI 新闻", "11/11/2026", null, TODAY));
    }

    @Test
    void normalizesProviderPublicationDatesWithoutUsingCrawlDates() {
        assertEquals("2026-11-11", WebSearchService.normalizePublicationDate("2026-11-11T09:30:00+08:00"));
        assertEquals("2026-11-11", WebSearchService.normalizePublicationDate("Tue, 10 Nov 2026 23:00:00 GMT",
                ZoneId.of("Asia/Shanghai")));
        assertEquals("2026-11-10", WebSearchService.normalizePublicationDate("2026-11-10"));
        assertNull(WebSearchService.normalizePublicationDate(""));
    }

    @Test
    void extractsPublishedDateFromArticleMetadataAndJsonLd() {
        String meta = "<html><head><meta property=\"article:published_time\" content=\"2026-11-11T08:00:00+08:00\"></head></html>";
        assertEquals("2026-11-11", WebSearchService.extractPublishedDate(meta));

        String jsonLd = "<script type=\"application/ld+json\">"
                + "{\"@type\":\"NewsArticle\",\"datePublished\":\"2026-11-10T23:00:00Z\"}"
                + "</script>";
        assertEquals("2026-11-11", WebSearchService.extractPublishedDate(jsonLd));
    }

    @Test
    void todayWindowAddsExactSearchBoundsAndDropsYesterdayButFlagsUnknown() throws Exception {
        WebSearchService.DateWindow window = WebSearchService.resolveDateWindow(
                "帮我搜索今日 AI 技术最新动态", null, null, TODAY);
        String boundedQuery = WebSearchService.withDateWindow("AI 技术 最新动态", window);
        assertEquals(true, boundedQuery.contains("after:2026-11-10 before:2026-11-12"));

        List<WebSearchService.SearchResult> filtered = WebSearchService.filterDateWindow(List.of(
                new WebSearchService.SearchResult("昨天发布的旧消息", "https://example.test/old", "", 1, "2026-11-10"),
                new WebSearchService.SearchResult("今日发布的新闻", "https://example.test/today", "", 2, "2026-11-11"),
                new WebSearchService.SearchResult("没有发布日期", "https://example.test/unknown", "", 3, null)
        ), window, 10);

        assertEquals(2, filtered.size());
        assertEquals("2026-11-11", filtered.get(0).publishedDate());
        JsonNode response = new ObjectMapper().readTree(WebSearchService.buildSuccessResponse(
                filtered, window, ZoneId.of("Asia/Shanghai")));
        assertEquals("2026-11-11..2026-11-11", response.path("date_window").asText());
        assertEquals("2026-11-11", response.path("data").path("web").get(0).path("publication_date").asText());
        assertEquals(false, response.path("data").path("web").get(0).path("date_verified").asBoolean());
        assertEquals("unknown", response.path("data").path("web").get(1).path("publication_date").asText());
        assertEquals(false, response.path("data").path("web").get(1).path("date_verified").asBoolean());
    }
}
