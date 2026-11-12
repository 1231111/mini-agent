package com.miniagent.agent.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import com.miniagent.agent.security.GuardedHttpClient;
import com.miniagent.agent.security.NetworkGuard;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebSearchServiceDateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final SearchDateContext.Window TODAY_REQUEST = new SearchDateContext.Window(
            TODAY, TODAY, ZONE, Instant.parse("2026-11-12T03:00:00Z"));

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
        assertEquals(new WebSearchService.DateWindow(TODAY.minusDays(1), TODAY),
                WebSearchService.resolveDateWindow("AI 技术新闻", "2026-11-11", "2026-11-12", TODAY, null));
        assertEquals(new WebSearchService.DateWindow(TODAY, TODAY),
                WebSearchService.resolveDateWindow("AI 新闻 2026-11-12", null, null, TODAY));
    }

    @Test
    void trustedTodayWindowSurvivesRewritesAndBroadenedModelParameters() {
        for (String query : List.of("帮我搜索今日 AI 技术最新动态", "latest AI technology news",
                "人工智能发布 过去48小时", "AI news 2026-11-10..2026-11-12")) {
            assertEquals(new WebSearchService.DateWindow(TODAY, TODAY),
                    WebSearchService.resolveDateWindow(query, "2026-11-10", "2026-11-12", TODAY,
                            TODAY_REQUEST), query);
        }
        assertEquals(new WebSearchService.DateWindow(TODAY, TODAY),
                WebSearchService.resolveDateWindow("latest AI technology news", null, null, TODAY,
                        TODAY_REQUEST));
    }

    @Test
    void modelCanNarrowButCannotEscapeTrustedRequestWindow() {
        SearchDateContext.Window twoDays = new SearchDateContext.Window(TODAY.minusDays(1), TODAY,
                ZONE, TODAY_REQUEST.asOf());
        assertEquals(new WebSearchService.DateWindow(TODAY, TODAY),
                WebSearchService.resolveDateWindow("AI news", "2026-11-12", "2026-11-12", TODAY, twoDays));
        assertThrows(IllegalArgumentException.class, () -> WebSearchService.resolveDateWindow(
                "AI news", "2026-11-10", "2026-11-11", TODAY, TODAY_REQUEST));
    }

    @Test
    void searchRejectsOutOfWindowDatesBeforeCallingProvider() throws Exception {
        try (SearchDateContext.Scope ignored = SearchDateContext.bind(TODAY_REQUEST)) {
            JsonNode response = new ObjectMapper().readTree(new WebSearchService().search(
                    "latest AI technology news", 5, "2026-11-10", "2026-11-11"));
            assertEquals(false, response.path("success").asBoolean());
            assertEquals("DATE_WINDOW_CONFLICT", response.path("error_code").asText());
            assertEquals(true, response.path("error").asText().contains("2026-11-12..2026-11-12"));
        }
    }

    @Test
    void searchUsesRequestSnapshotInsteadOfCurrentMachineDayForRelativeQueries() throws Exception {
        try (SearchDateContext.Scope ignored = SearchDateContext.bind(TODAY_REQUEST)) {
            JsonNode response = new ObjectMapper().readTree(new WebSearchService().search(
                    "yesterday AI news", 5));
            assertEquals("DATE_WINDOW_CONFLICT", response.path("error_code").asText());
            assertEquals(true, response.path("error").asText().contains("2026-11-11..2026-11-11"));
        }
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
        assertEquals(true, boundedQuery.contains("after:2026-11-11 before:2026-11-13"));

        List<WebSearchService.SearchResult> filtered = WebSearchService.filterDateWindow(List.of(
                new WebSearchService.SearchResult("昨天发布的旧消息", "https://example.test/old", "", 1, "2026-11-11"),
                new WebSearchService.SearchResult("今日发布的新闻", "https://example.test/today", "", 2, "2026-11-12"),
                new WebSearchService.SearchResult("没有发布日期", "https://example.test/unknown", "", 3, null)
        ), window, 10);

        assertEquals(2, filtered.size());
        assertEquals("2026-11-12", filtered.get(0).publishedDate());
        JsonNode response = new ObjectMapper().readTree(WebSearchService.buildSuccessResponse(
                filtered, window, ZoneId.of("Asia/Shanghai")));
        assertEquals("2026-11-12..2026-11-12", response.path("date_window").asText());
        assertEquals("2026-11-12", response.path("data").path("web").get(0).path("publication_date").asText());
        assertEquals(false, response.path("data").path("web").get(0).path("date_verified").asBoolean());
        assertEquals("unknown", response.path("data").path("web").get(1).path("publication_date").asText());
        assertEquals(false, response.path("data").path("web").get(1).path("date_verified").asBoolean());
        assertEquals(false, response.path("data").path("web").get(0).path("citation_eligible").asBoolean());
        assertEquals(false, response.path("data").path("web").get(1).path("within_requested_window").asBoolean());
    }

    @Test
    void resolvedWindowReplacesConflictingModelQueryOperators() {
        var window = new WebSearchService.DateWindow(TODAY, TODAY);
        String query = WebSearchService.withDateWindow(
                "AI news after:2026-11-09 before:2026-11-11", window);
        assertEquals(false, query.contains("2026-11-09"));
        assertEquals(false, query.contains("before:2026-11-11"));
        assertEquals(1, query.split("after:", -1).length - 1);
        assertEquals(1, query.split("before:", -1).length - 1);
        assertEquals(true, query.contains("after:2026-11-11 before:2026-11-13"));
    }

    @Test
    void extractMarksOldUnknownAndCurrentPublicationEligibilityUsingTrustedZone() throws Exception {
        WebSearchService service = new WebSearchService();
        NetworkGuard guard = mock(NetworkGuard.class);
        GuardedHttpClient client = mock(GuardedHttpClient.class);
        ReflectionTestUtils.setField(service, "networkGuard", guard);
        ReflectionTestUtils.setField(service, "guardedHttpClient", client);
        ReflectionTestUtils.setField(service, "configuredBackend", "bing");
        // UTC differs from the request zone: 16:30Z is already the next day in Shanghai.
        ReflectionTestUtils.setField(service, "configuredTimeZone", "UTC");
        String url = "https://example.test/news";
        try (SearchDateContext.Scope ignored = SearchDateContext.bind(TODAY_REQUEST)) {
            for (String publicationDate : List.of("2026-11-11T08:00:00+08:00", "", "2026-11-11T16:30:00Z")) {
                String html = "<html><head><title>AI news</title>"
                        + (publicationDate.isEmpty() ? "" : "<meta property=\"article:published_time\" content=\""
                        + publicationDate + "\">") + "</head><body>Article</body></html>";
                when(client.get(eq(url), any(), any(), anyInt())).thenReturn(new GuardedHttpClient.Response(
                        200, URI.create(url), HttpHeaders.of(Map.of(), (name, value) -> true),
                        html.getBytes(StandardCharsets.UTF_8)));
                JsonNode response = new ObjectMapper().readTree(service.extract(url));
                boolean eligible = publicationDate.equals("2026-11-11T16:30:00Z");
                assertEquals(true, response.path("success").asBoolean());
                assertEquals("2026-11-12..2026-11-12", response.path("requested_date_window").asText());
                assertEquals("Asia/Shanghai", response.path("time_zone").asText());
                assertEquals(eligible, response.path("within_requested_window").asBoolean(), publicationDate);
                assertEquals(eligible, response.path("citation_eligible").asBoolean(), publicationDate);
            }
        }
    }
}
