package com.miniagent.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.impl.WebSearchParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WebSearchServiceImageCandidatesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private WebSearchService service(JsonNode response, AtomicReference<HttpRequest> request) throws Exception {
        var service = spy(new WebSearchService());
        ReflectionTestUtils.setField(service, "configuredBackend", "tavily");
        ReflectionTestUtils.setField(service, "configuredTimeZone", ZONE.getId());
        ReflectionTestUtils.setField(service, "tavilyApiKey", "isolated-test-key");
        doAnswer(invocation -> { request.set(invocation.getArgument(0)); return response; })
                .when(service).executeTavilySearch(any());
        return service;
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void optionalImagesAreRequestedAndReturnedOnlyWhenExplicitlyEnabled(boolean includeImages) throws Exception {
        var request = new AtomicReference<HttpRequest>();
        var service = service(JSON.readTree("""
                {"results":[{"title":"电影列表","url":"https://movies.example.com/list","content":"电影资料"}],
                 "images":[{"url":"https://cdn.example.com/wick4.jpg","title":"John Wick 4 poster",
                   "description":"Chapter 4 movie poster"}]}
                """), request);
        JsonNode result = JSON.readTree(service.search("电影海报", 5, null, null, includeImages));
        assertTrue(result.path("success").asBoolean());
        assertEquals("POST", request.get().method());
        assertEquals("https://api.tavily.com/search", request.get().uri().toString());
        JsonNode payload = JSON.readTree(requestBody(request.get()));
        assertEquals(includeImages, payload.path("include_images").asBoolean(false));
        assertEquals(includeImages, payload.path("include_image_descriptions").asBoolean(false));
        assertEquals("电影海报", payload.path("query").asText());
        assertEquals(5, payload.path("max_results").asInt());
        assertFalse(payload.path("include_raw_content").asBoolean());
        assertEquals(includeImages, result.path("data").has("images"));
        assertFalse(result.path("data").path("web").get(0).has("thumbnail_url"));
        if (includeImages) {
            JsonNode image = result.path("data").path("images").get(0);
            assertEquals("https://cdn.example.com/wick4.jpg", image.path("url").asText());
            assertEquals("John Wick 4 poster", image.path("title").asText());
            assertEquals("Chapter 4 movie poster", image.path("description").asText());
            assertEquals("unassociated", image.path("source_association").asText());
            assertFalse(image.has("source_url"));
        }
        verify(service).executeTavilySearch(any());
    }

    @Test void existingSearchSignaturesDoNotRequestImagesByDefault() throws Exception {
        var request = new AtomicReference<HttpRequest>();
        var service = service(JSON.readTree("{\"results\":[],\"images\":[\"https://cdn.example.com/poster.jpg\"]}"), request);
        JsonNode first = JSON.readTree(service.search("电影", 3));
        assertFalse(JSON.readTree(requestBody(request.get())).has("include_images"));
        assertFalse(first.path("data").has("images"));
        service.search("电影", 3, null, null);
        assertFalse(JSON.readTree(requestBody(request.get())).has("include_images"));
        verify(service, times(2)).executeTavilySearch(any());
    }

    @Test void independentImageCandidatesRetainVersionEvidenceWithoutInferredWebAssociation() throws Exception {
        JsonNode provider = JSON.readTree("""
                {"results":[{"title":"John Wick 4","url":"https://movies.example.com/wick4"}],
                 "images":[
                   {"url":"https://cdn.example.com/wick2.jpg","title":"John Wick 2 poster",
                     "description":"The title Chapter 2 is visible"},
                   {"url":"https://cdn.example.com/wick4.jpg","title":"John Wick 4 poster",
                     "description":"The title Chapter 4 is visible","source_url":"https://movies.example.com/wick4"}
                 ]}
                """);
        var images = WebSearchService.mapTavilyImages(provider);
        var web = WebSearchService.mapTavilyResults(provider, ZONE);
        JsonNode data = JSON.readTree(WebSearchService.buildSuccessResponse(web, null, ZONE, images)).path("data");
        assertEquals(2, data.path("images").size());
        assertEquals("unassociated", data.path("images").get(0).path("source_association").asText());
        assertEquals("John Wick 2 poster", data.path("images").get(0).path("title").asText());
        assertFalse(data.path("images").get(0).has("source_url"));
        assertEquals("provider_explicit", data.path("images").get(1).path("source_association").asText());
        assertEquals("https://movies.example.com/wick4", data.path("images").get(1).path("source_url").asText());
        // The only web association is the one explicitly provided by the provider, never image position/title.
        assertEquals("https://cdn.example.com/wick4.jpg", data.path("web").get(0).path("thumbnail_url").asText());
    }

    @Test void candidatesRejectUnsafeAndRelativeAddressesDeduplicateAndDoNotInventSourceUrls() throws Exception {
        JsonNode provider = JSON.readTree("""
                {"images":["https://cdn.example.com/poster.jpg#preview",
                  {"url":"https://cdn.example.com/poster.jpg","title":"duplicate"},
                  {"url":"https://cdn.example.com/other.jpg","source_url":"http://127.0.0.1/private"},
                  {"url":"/relative.jpg"},"//cdn.example.com/inferred.jpg","javascript:alert(1)",
                  "http://cdn.example.com/cleartext.jpg","https://localhost/secret.jpg",
                  "https://user:password@cdn.example.com/credentials.jpg","data:image/png;base64,AAA",
                  "https://192.168.0.1/private.jpg","https://cdn.example.com:8080/port.jpg"]}
                """);
        var images = WebSearchService.mapTavilyImages(provider);
        assertEquals(2, images.size());
        assertEquals("https://cdn.example.com/poster.jpg", images.get(0).url());
        assertEquals("https://cdn.example.com/other.jpg", images.get(1).url());
        assertNull(images.get(0).sourceUrl());
        assertNull(images.get(1).sourceUrl());
    }

    @Test void candidateMetadataIsBoundedAndPrioritizedBeforeLongWebResults() throws Exception {
        var provider = JSON.createObjectNode();
        var candidates = provider.putArray("images");
        for (int n = 0; n < 15; n++) candidates.addObject().put("url", "https://cdn.example.com/" + n + ".jpg")
                .put("title", "t".repeat(1000)).put("description", "d".repeat(10000));
        var images = WebSearchService.mapTavilyImages(provider);
        assertTrue(!images.isEmpty() && images.size() < 10);
        assertTrue(images.stream().allMatch(image -> image.title().length() <= 200 && image.description().length() <= 600));
        String serialized = WebSearchService.buildSuccessResponse(List.of(new WebSearchService.SearchResult(
                "电影", "https://movies.example.com/list", "c".repeat(10000), 1, null)), null, ZONE,
                images);
        assertTrue(serialized.indexOf("\"images\"") < serialized.indexOf("\"web\""));
        assertTrue(serialized.substring(0, 8000).contains("https://cdn.example.com/0.jpg"));
        assertTrue(JSON.readTree(serialized).path("data").path("images").toString().length()
                <= WebSearchService.MAX_IMAGE_CANDIDATE_JSON_CHARS);
        assertTrue(serialized.substring(0, 8000).contains("\"title\":\"电影\""));
        assertTrue(serialized.substring(0, 8000).contains("c".repeat(1000)));
    }

    @Test void longImageUrlsAndEscapedMetadataCannotExhaustTheWebFactBudget() throws Exception {
        var provider = JSON.createObjectNode();
        var candidates = provider.putArray("images");
        for (int n = 0; n < 10; n++) candidates.addObject()
                .put("url", "https://cdn.example.com/" + "q".repeat(900) + n + ".jpg")
                .put("title", "\"\\".repeat(200)).put("description", "\"\\\n".repeat(600));
        var images = WebSearchService.mapTavilyImages(provider);
        assertFalse(images.isEmpty());
        String serialized = WebSearchService.buildSuccessResponse(List.of(new WebSearchService.SearchResult(
                "电影事实", "https://movies.example.com/details", "来源正文".repeat(2000), 1, null)), null, ZONE, images);
        assertTrue(JSON.readTree(serialized).path("data").path("images").toString().length()
                <= WebSearchService.MAX_IMAGE_CANDIDATE_JSON_CHARS);
        assertTrue(serialized.substring(0, 8000).contains("\"title\":\"电影事实\""));
        assertTrue(serialized.substring(0, 8000).contains("来源正文".repeat(100)));
    }

    @Test void overBudgetImageIsSkippedWithoutTruncatingUrlAndLaterShortCandidateStillFits() throws Exception {
        String oversizedUrl = "https://cdn.example.com/" + "q".repeat(3800) + ".jpg";
        assertNotNull(WebSearchService.safeThumbnailUrl(oversizedUrl, null));
        var provider = JSON.createObjectNode();
        provider.putArray("images").addObject().put("url", oversizedUrl);
        provider.withArray("images").addObject().put("url", "https://cdn.example.com/real-poster.jpg")
                .put("title", "电影海报");
        var images = WebSearchService.mapTavilyImages(provider);
        assertEquals(1, images.size());
        assertEquals("https://cdn.example.com/real-poster.jpg", images.get(0).url());
        assertEquals("电影海报", images.get(0).title());
    }

    @Test void absentProviderImagesOmitCandidateArrayAndToolParameterDefaultsRemainCompatible() throws Exception {
        assertTrue(WebSearchService.mapTavilyImages(JSON.readTree("{\"results\":[]}")).isEmpty());
        JsonNode data = JSON.readTree(WebSearchService.buildSuccessResponse(List.of(), null, ZONE)).path("data");
        assertFalse(data.has("images"));
        assertNull(JSON.readValue("{\"query\":\"电影\"}", WebSearchParams.class).getIncludeImages());
        assertEquals(Boolean.TRUE, JSON.readValue("{\"query\":\"电影\",\"includeImages\":true}", WebSearchParams.class).getIncludeImages());
    }

    private static String requestBody(HttpRequest request) throws Exception {
        var completion = new CompletableFuture<String>();
        var body = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) {
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); body.writeBytes(bytes);
            }
            public void onError(Throwable error) { completion.completeExceptionally(error); }
            public void onComplete() { completion.complete(body.toString(StandardCharsets.UTF_8)); }
        });
        return completion.get(1, TimeUnit.SECONDS);
    }
}
