package com.miniagent.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.security.GuardedHttpClient;
import com.miniagent.agent.security.NetworkGuard;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSearchServiceThumbnailTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void bingThumbnailRemainsAttachedToItsOwnResultThroughSerialization() throws Exception {
        var results = WebSearchService.mapBingResults(JSON.readTree("""
                {"webPages":{"value":[
                  {"name":"AI news","url":"https://news.example.com/ai","snippet":"Summary",
                   "datePublished":"2026-11-16T08:00:00+08:00",
                   "thumbnail":{"contentUrl":"https://cdn.example.com/ai.jpg"}},
                  {"name":"No image","url":"https://news.example.com/plain"}
                ]}}
                """), ZONE);
        JsonNode web = response(results).path("data").path("web");
        assertEquals("https://cdn.example.com/ai.jpg", web.get(0).path("thumbnail_url").asText());
        assertEquals("https://news.example.com/ai", web.get(0).path("thumbnail_source_url").asText());
        assertEquals("bing_metadata", web.get(0).path("thumbnail_source").asText());
        assertEquals("2026-11-16", web.get(0).path("publication_date").asText());
        assertFalse(web.get(0).path("date_verified").asBoolean());
        assertFalse(web.get(1).has("thumbnail_url"));
    }

    @Test
    void googleReadsBothCseThumbnailAndOpenGraphMetadata() throws Exception {
        var results = WebSearchService.mapGoogleResults(JSON.readTree("""
                {"items":[
                  {"title":"CSE","link":"https://news.example.com/one",
                   "pagemap":{"cse_thumbnail":[{"src":"https://cdn.example.com/one.jpg"}]}},
                  {"title":"OG","link":"https://news.example.com/two",
                   "pagemap":{"metatags":[{"og:image":"/covers/two.jpg"}]}},
                  {"title":"Invalid","link":"https://news.example.com/three",
                   "pagemap":{"cse_image":[{"src":"javascript:alert(1)"}]}},
                  {"title":"Full image","link":"https://news.example.com/four",
                   "pagemap":{"cse_image":[{"src":"/covers/four.jpg"}]}}
                ]}
                """), ZONE);
        assertEquals("https://cdn.example.com/one.jpg", results.get(0).thumbnailUrl());
        assertEquals("https://news.example.com/covers/two.jpg", results.get(1).thumbnailUrl());
        assertNull(results.get(2).thumbnailUrl());
        assertEquals("https://news.example.com/covers/four.jpg", results.get(3).thumbnailUrl());
    }

    @Test
    void tavilyDoesNotAttachUnassociatedOrMismatchedImages() throws Exception {
        var results = WebSearchService.mapTavilyResults(JSON.readTree("""
                {"results":[
                  {"title":"One","url":"https://news.example.com/one"},
                  {"title":"Two","url":"https://news.example.com/two"},
                  {"title":"Three","url":"https://news.example.com/three",
                   "thumbnail":{"source_url":"https://news.example.com/other","url":"https://cdn.example.com/wrong.jpg"}},
                  {"title":"Four","url":"https://news.example.com/four","thumbnail_url":"https://cdn.example.com/four.jpg"}
                ], "images":[
                  "https://cdn.example.com/unassociated.jpg",
                  {"url":"https://cdn.example.com/no-source.jpg"},
                  {"source_url":"https://news.example.com/one?other=1","url":"https://cdn.example.com/wrong-query.jpg"},
                  {"source_url":"https://NEWS.example.com:443/two#heading","url":"https://cdn.example.com/two.jpg"},
                  {"source_url":"https://news.example.com/other","url":"https://cdn.example.com/other.jpg"}
                ]}
                """), ZONE);
        assertNull(results.get(0).thumbnailUrl());
        assertEquals("https://cdn.example.com/two.jpg", results.get(1).thumbnailUrl());
        assertEquals("tavily_source_association", results.get(1).thumbnailSource());
        assertNull(results.get(2).thumbnailUrl());
        assertFalse(response(results).path("data").path("web").get(2).has("thumbnail_url"));
        assertEquals("https://cdn.example.com/four.jpg", results.get(3).thumbnailUrl());
        assertEquals("tavily_result_metadata", results.get(3).thumbnailSource());
    }

    @Test
    void duckDuckGoResolvesItsRelativeImagesWithoutReusingThemForOtherTopics() throws Exception {
        var results = WebSearchService.mapDuckDuckGoResults(JSON.readTree("""
                {"Heading":"AI", "AbstractURL":"https://news.example.com/ai", "AbstractText":"AI summary",
                 "Image":"/i/ai.jpg", "RelatedTopics":[
                   {"Text":"One. Summary", "FirstURL":"https://news.example.com/one", "Icon":{"URL":"/i/one.jpg"}},
                   {"Text":"Two. Summary", "FirstURL":"https://news.example.com/two", "Icon":{"URL":""}}
                 ]}
                """), "AI", 5);
        assertEquals("https://duckduckgo.com/i/ai.jpg", results.get(0).thumbnailUrl());
        assertEquals("https://duckduckgo.com/i/one.jpg", results.get(1).thumbnailUrl());
        assertNull(results.get(2).thumbnailUrl());
    }

    @Test
    void safeThumbnailUrlsAcceptRelativeAndProtocolRelativeImagesButRejectUnsafeTargets() {
        String page = "https://news.example.com/articles/ai";
        assertEquals("https://news.example.com/cover.jpg?size=small&lang=zh",
                WebSearchService.safeThumbnailUrl("../cover.jpg?size=small&lang=zh#preview", page));
        assertEquals("https://cdn.example.com/cover.jpg",
                WebSearchService.safeThumbnailUrl("//cdn.example.com/cover.jpg", page));
        assertEquals("https://cdn.example.com/cover.jpg?value=1&amp;literal=2",
                WebSearchService.safeThumbnailUrl("https://cdn.example.com/cover.jpg?value=1&amp;literal=2", page));
        assertEquals("https://cdn.example.com/cover.jpg",
                WebSearchService.safeThumbnailUrl("https://cdn.example.com/cover.jpg", "http://news.example.com/article"));
        for (String bad : List.of("javascript:alert(1)", "data:image/png;base64,AA==", "file:///C:/secret.png",
                "https://user:secret@cdn.example.com/cover.jpg", "http://localhost/image.jpg",
                "http://127.0.0.1/image.jpg", "http://2130706433/image.jpg", "http://0x7f.1/image.jpg",
                "http://[::1]/image.jpg", "http://metadata.google.internal/image.jpg",
                "https://10.1.2.3/image.jpg", "https://192.168.1.1/image.jpg", "https://0177.0.0.1/image.jpg",
                "http://cdn.example.com/image.jpg",
                "http://photos.local/image.jpg", "https://cdn.example.com:8080/image.jpg",
                "https://cdn.example.com\\@localhost/image.jpg", "https://cdn.example.com/\nimage.jpg",
                "?query=only", "#fragment")) {
            assertNull(WebSearchService.safeThumbnailUrl(bad, page), bad);
        }
    }

    @Test
    void pageMetadataDecodesEntitiesAndResolvesCoversWithoutUsingPublisherLogos() {
        String html = """
                <TITLE>AI &amp; Technology</TITLE>
                <meta property="article:published_time" content="2026-11-16T09:00:00+08:00">
                <meta content="/images/ai.jpg?size=300&amp;lang=zh" property="og:image">
                """;
        var metadata = WebSearchService.extractPageMetadata(html, "https://news.example.com/articles/ai", ZONE);
        assertEquals("AI & Technology", metadata.title());
        assertEquals("2026-11-16", metadata.publicationDate());
        assertEquals("https://news.example.com/images/ai.jpg?size=300&lang=zh", metadata.thumbnailUrl());
        var structured = WebSearchService.extractPageMetadata("""
                <script type="application/ld+json">{"@graph":[
                  {"@type":"Organization","image":"https://cdn.example.com/logo.jpg"},
                  {"@type":"NewsArticle","headline":"Actual article",
                   "image":{"@type":"ImageObject","contentUrl":"/article.jpg"},
                   "publisher":{"image":"https://cdn.example.com/publisher.jpg"}}
                ]}</script>
                """, "https://news.example.com/articles/ai", ZONE);
        assertEquals("Actual article", structured.title());
        assertEquals("https://news.example.com/article.jpg", structured.thumbnailUrl());
    }

    @Test
    void metadataHandlesDuplicateOpenGraphAndTwitterFallbackBeforeNestedArticleImages() {
        String page = "https://news.example.com/articles/ai";
        var duplicate = WebSearchService.extractPageMetadata("""
                <meta property="og:image" content="javascript:bad()">
                <meta property="og:image" content="../covers/ai.jpg?w=600&amp;lang=zh">
                """, page, ZONE);
        assertEquals("https://news.example.com/covers/ai.jpg?w=600&lang=zh", duplicate.thumbnailUrl());
        var twitter = WebSearchService.extractPageMetadata("""
                <meta property="og:image" content="http://cdn.example.com/not-permitted-by-csp.jpg">
                <meta name="twitter:image" content="//cdn.example.com/twitter.webp">
                <meta name="twitter:title" content="AI &#x65B0;闻">
                """, page, ZONE);
        assertEquals("https://cdn.example.com/twitter.webp", twitter.thumbnailUrl());
        assertEquals("AI 新闻", twitter.title());
        var article = WebSearchService.extractPageMetadata("""
                <script type="application/ld+json">{"@graph":[
                  {"@type":"Organization","image":"https://cdn.example.com/logo.jpg"},
                  {"@type":"WebPage","mainEntity":{"@type":["Thing","https://schema.org/NewsArticle"],
                    "headline":"实际新闻", "image":[{"@type":"ImageObject","contentUrl":"/article.jpg"}]}}
                ]}</script>
                """, page, ZONE);
        assertEquals("实际新闻", article.title());
        assertEquals("https://news.example.com/article.jpg", article.thumbnailUrl());
    }

    @Test
    void malformedOrMissingMetadataNeverProducesInventedCover() {
        for (String html : List.of("<title>No cover</title><img src=\"/banner.jpg\">",
                "<meta property=\"og:image\" content=\"javascript:alert(1)\">",
                "<script type=\"application/ld+json\">{broken</script>",
                "<script type=\"application/ld+json\">{\"@type\":\"Organization\",\"image\":\"/logo.jpg\"}</script>")) {
            assertNull(WebSearchService.extractPageMetadata(html, "https://news.example.com/page", ZONE).thumbnailUrl(), html);
        }
    }

    @Test
    void extractionUsesRedirectDestinationForRelativeCoverAndDoesNotFetchTheImage() throws Exception {
        var service = new WebSearchService();
        var guard = mock(NetworkGuard.class);
        var client = mock(GuardedHttpClient.class);
        ReflectionTestUtils.setField(service, "networkGuard", guard);
        ReflectionTestUtils.setField(service, "guardedHttpClient", client);
        ReflectionTestUtils.setField(service, "configuredBackend", "bing");
        ReflectionTestUtils.setField(service, "configuredTimeZone", ZONE.getId());
        String requestUrl = "https://short.example.com/story";
        String actualUrl = "https://news.example.com/articles/ai";
        String html = "<title>AI story</title><meta property=\"og:image\" content=\"../covers/ai.jpg\">";
        when(client.get(eq(requestUrl), any(), any(), anyInt())).thenReturn(new GuardedHttpClient.Response(
                200, URI.create(actualUrl), HttpHeaders.of(Map.of(), (name, value) -> true),
                html.getBytes(StandardCharsets.UTF_8)));
        JsonNode extracted = JSON.readTree(service.extract(requestUrl));
        assertTrue(extracted.path("success").asBoolean());
        assertEquals("https://news.example.com/covers/ai.jpg", extracted.path("thumbnail_url").asText());
        assertEquals(actualUrl, extracted.path("thumbnail_source_url").asText());
        assertEquals("source_page_metadata", extracted.path("thumbnail_source").asText());
        verify(client, times(1)).get(eq(requestUrl), any(), any(), anyInt());
        verifyNoMoreInteractions(client);
        verify(guard, times(1)).validateUrl(requestUrl);
        verifyNoMoreInteractions(guard);
    }

    @Test
    void existingSourceDateFetchAlsoProvidesCoverAndTitleWithoutASecondRequest() throws Exception {
        var service = new WebSearchService();
        var guard = mock(NetworkGuard.class);
        var client = mock(GuardedHttpClient.class);
        ReflectionTestUtils.setField(service, "networkGuard", guard);
        ReflectionTestUtils.setField(service, "guardedHttpClient", client);
        ReflectionTestUtils.setField(service, "configuredTimeZone", ZONE.getId());
        String page = "https://news.example.com/article";
        String html = "<title>原文标题</title><meta property='og:image' content='/cover.png'>"
                + "<meta property='article:published_time' content='2026-11-16'>";
        when(client.get(eq(page), any(), any(), anyInt())).thenReturn(new GuardedHttpClient.Response(
                200, URI.create(page), HttpHeaders.of(Map.of(), (name, value) -> true),
                html.getBytes(StandardCharsets.UTF_8)));
        var metadata = service.extractSourcePageMetadata(page);
        assertEquals("原文标题", metadata.title());
        assertEquals("2026-11-16", metadata.publicationDate());
        assertEquals("https://news.example.com/cover.png", metadata.thumbnailUrl());
        verify(guard).validateUrl(page);
        verify(client).get(eq(page), any(), eq(java.time.Duration.ofSeconds(12)), eq(2 * 1024 * 1024));
        verifyNoMoreInteractions(guard, client);
    }

    private static JsonNode response(List<WebSearchService.SearchResult> results) throws Exception {
        return JSON.readTree(WebSearchService.buildSuccessResponse(results, null, ZONE));
    }
}
