package com.miniagent.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.agent.security.GuardedHttpClient;
import com.miniagent.agent.security.NetworkGuard;
import com.miniagent.agent.tool.ToolExecutionDeadline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * 网络搜索服务 — 对标 hermes-agent 的 web_search / web_extract
 *
 * 架构：
 *   web_search → 后端路由（Bing / Google / DuckDuckGo）→ 标准化 JSON 结果
 *   web_extract → HttpClient 抓取页面 → 清洗提取纯文本/HTML
 *
 * 结果格式完全对标 hermes-agent：
 *   {"success": true, "data": {"web": [{"title", "url", "description", "position",
 *       "publication_date", "publication_date_source", "date_verified",
 *       "thumbnail_url"?, "thumbnail_source_url"?, "thumbnail_source"?}]}}
 *
 * 环境变量：
 *   BING_API_KEY        — Bing Web Search API 密钥（首选）
 *   GOOGLE_API_KEY      — Google Custom Search API 密钥
 *   GOOGLE_CSE_ID       — Google Custom Search Engine ID
 *   WEB_SEARCH_BACKEND  — 后端选择：bing / google / duckduckgo（默认自动）
 */
@Slf4j
@Service
public class WebSearchService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_RESULTS = 10;
    // web_search is clamped to 8,000 characters downstream; reserve space for web facts.
    static final int MAX_IMAGE_CANDIDATE_JSON_CHARS = 3000;
    private static final int MAX_CONTENT_LENGTH = 15000; // web_extract 单页最大字符数
    private static final Pattern META_TAG = Pattern.compile("(?is)<meta\\b[^>]*>");
    private static final Pattern TITLE_TAG = Pattern.compile("(?is)<title\\b[^>]*>(.*?)</title\\s*>");
    private static final Pattern HTML_ATTRIBUTE = Pattern.compile("(?is)([\\w:-]+)\\s*=\\s*(['\\\"])(.*?)\\2");
    private static final Pattern HTML_ENTITY = Pattern.compile("&(#(?:x[0-9a-fA-F]+|[0-9]+)|amp|quot|apos|lt|gt|nbsp);", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_LD = Pattern.compile("(?is)<script\\b[^>]*type\\s*=\\s*(['\\\"])application/ld\\+json\\1[^>]*>(.*?)</script>");
    private static final Pattern DATE_TOKEN = Pattern.compile("(?<!\\d)(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?(?!\\d)");
    private static final Pattern ISO_DATE_IN_QUERY = Pattern.compile("(?<!\\d)(20\\d{2}-\\d{2}-\\d{2})(?!\\d)");
    private static final Pattern SEARCH_DATE_OPERATOR = Pattern.compile(
            "(?i)(?<![\\w:])(?:after|before):\\s*\\d{4}-\\d{1,2}-\\d{1,2}(?![\\d-])");
    private static final Pattern PUBLISH_DATE_KEY = Pattern.compile("(?i)^(article:published_time|og:published_time|datepublished|date|pubdate|parsely-pub-date|sailthru\\.date|dc\\.date\\.issued|citation_publication_date|published_time)$");
    private static final Set<String> IMAGE_CONTENT_TYPES = Set.of("article", "newsarticle", "techarticle",
            "reportagenewsarticle", "analysisnewsarticle", "opinionnewsarticle", "reviewnewsarticle", "scholarlyarticle",
            "blogposting", "liveblogposting", "webpage", "itempage", "creativework", "product", "videoobject",
            "movie", "musicrecording", "musicalbum", "recipe", "book", "event", "report");

    /** web_extract 的出网判定与传输：每跳校验 + 有界响应体。 */
    @Autowired
    private NetworkGuard networkGuard;
    @Autowired
    private GuardedHttpClient guardedHttpClient;

    // ========== 环境变量配置 ==========

    @Value("${web.search.tavily-api-key:#{null}}")
    private String tavilyApiKey;

    @Value("${web.search.bing-api-key:#{null}}")
    private String bingApiKey;

    @Value("${web.search.google-api-key:#{null}}")
    private String googleApiKey;

    @Value("${web.search.google-cse-id:#{null}}")
    private String googleCseId;

    @Value("${web.search.deepseek-api-key:#{null}}")
    private String deepSeekApiKey;

    @Value("${web.search.deepseek-base-url:https://api.deepseek.com}")
    private String deepSeekBaseUrl = "https://api.deepseek.com";

    @Value("${web.search.backend:}")
    private String configuredBackend;

    @Value("${web.search.time-zone:Asia/Shanghai}")
    private String configuredTimeZone;

    /**
     * 搜索网页 — 对标 hermes-agent web_search
     *
     * @param query 搜索关键词
     * @param limit 返回结果数量（默认5）
     * @return 标准化 JSON 结果
     */
    public String search(String query, Integer limit) {
        return search(query, limit, null, null);
    }

    /** 搜索网页，可按来源发布日期限定范围；未知发布日期会显式标注，供后续 web_extract 核验。 */
    public String search(String query, Integer limit, String dateFrom, String dateTo) {
        return search(query, limit, dateFrom, dateTo, false);
    }

    /** Optional provider images remain independent candidates, never positionally attached to web results. */
    public String search(String query, Integer limit, String dateFrom, String dateTo, boolean includeImages) {
        if (Objects.isNull(limit) || limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        int requestedLimit = Math.min(limit, MAX_RESULTS);
        SearchDateContext.Window requestedWindow = SearchDateContext.current();
        ZoneId searchZone = currentSearchZone();
        LocalDate today = requestedWindow == null ? LocalDate.now(searchZone)
                : requestedWindow.asOf().atZone(searchZone).toLocalDate();
        DateWindow dateWindow;
        try {
            dateWindow = resolveDateWindow(query, dateFrom, dateTo, today, requestedWindow);
        } catch (DateWindowConflictException e) {
            return buildErrorResponse("DATE_WINDOW_CONFLICT", e.getMessage());
        } catch (IllegalArgumentException e) {
            return buildErrorResponse(e.getMessage());
        }
        String backend = resolveBackend();
        String searchQuery = dateWindow == null ? query : withDateWindow(query, dateWindow);
        int fetchLimit = dateWindow == null ? requestedLimit : Math.min(20, requestedLimit * 3);
        log.info("Web搜索: query='{}' limit={} backend={} dateWindow={}", query, requestedLimit, backend, dateWindow);

        SearchProviderResult primary = null;
        Exception primaryFailure = null;
        try {
            ensureExtractionActive();
            primary = searchConfiguredBackend(backend, searchQuery, fetchLimit, dateWindow,
                    includeImages, searchZone);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return buildErrorResponse("SEARCH_INTERRUPTED", "网页搜索已中断，未启动后续请求");
        } catch (CancellationException e) {
            return buildErrorResponse("SEARCH_CANCELLED", "网页搜索已取消，未启动后续请求");
        } catch (ToolExecutionDeadline.Expired e) {
            return buildErrorResponse("SEARCH_DEADLINE", "网页搜索预算已耗尽，未启动后续请求");
        } catch (Exception e) {
            primaryFailure = e;
            log.warn("主搜索 provider 失败: backend={} type={}", backend, e.getClass().getSimpleName());
        }

        List<SearchResult> primaryResults = primary == null ? List.of() : primary.results();
        if (dateWindow != null) primaryResults = filterDateWindow(primaryResults, dateWindow, requestedLimit);
        else primaryResults = primaryResults.stream().limit(requestedLimit).toList();
        List<SearchResult> usablePrimary = usableSearchResults(primaryResults);
        if (!usablePrimary.isEmpty()) {
            try {
                return buildSuccessResponse(usablePrimary, dateWindow, searchZone,
                        primary == null ? List.of() : primary.images());
            } catch (Exception e) {
                return buildErrorResponse("搜索结果格式化失败: " + e.getMessage());
            }
        }

        // DeepSeek is a paid fallback: never call it after a usable primary result.
        // Empty DuckDuckGo Instant Answer placeholders are not treated as search results.
        if (StringUtils.isNotBlank(deepSeekApiKey)) {
            try {
                ensureExtractionActive();
                List<SearchResult> fallback = deepSeekSearch(searchQuery, fetchLimit, query, searchZone);
                if (dateWindow != null) fallback = filterDateWindow(fallback, dateWindow, requestedLimit);
                else fallback = fallback.stream().limit(requestedLimit).toList();
                fallback = usableSearchResults(fallback);
                String response = buildSuccessResponse(fallback, dateWindow, searchZone, List.of());
                return fallback.isEmpty() ? response : withFallbackMetadata(response, backend, "deepseek_search");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return buildErrorResponse("SEARCH_INTERRUPTED", "网页搜索已中断，未启动收费降级请求");
            } catch (CancellationException e) {
                return buildErrorResponse("SEARCH_CANCELLED", "网页搜索已取消，未启动收费降级请求");
            } catch (ToolExecutionDeadline.Expired e) {
                return buildErrorResponse("SEARCH_DEADLINE", "网页搜索预算已耗尽，未启动收费降级请求");
            } catch (Exception fallbackFailure) {
                log.warn("DeepSeek 搜索降级失败: type={}", fallbackFailure.getClass().getSimpleName());
                if (primaryFailure == null) {
                    // A successful-but-empty primary is a valid empty result, not an error.
                    try { return buildSuccessResponse(List.of(), dateWindow, searchZone); }
                    catch (Exception ignored) { return buildErrorResponse("搜索结果格式化失败"); }
                }
            }
        }

        if (primaryFailure != null) {
            log.error("Web搜索失败: backend={} error={}", backend, primaryFailure.getMessage());
            return buildErrorResponse("搜索失败: " + primaryFailure.getMessage()
                    + "。请检查 " + backend + " API 密钥是否正确配置。");
        }
        try { return buildSuccessResponse(usablePrimary, dateWindow, searchZone); }
        catch (Exception e) { return buildErrorResponse("搜索结果格式化失败: " + e.getMessage()); }
    }

    SearchProviderResult searchConfiguredBackend(String backend, String query, int limit,
                                                         DateWindow dateWindow, boolean includeImages,
                                                         ZoneId zone) throws Exception {
        if ("tavily".equals(backend)) {
            JsonNode provider = searchTavily(query, limit, includeImages);
            return new SearchProviderResult(mapTavilyResults(provider, zone),
                    includeImages ? mapTavilyImages(provider) : List.of());
        }
        List<SearchResult> results = switch (backend) {
            case "bing" -> searchBing(query, limit, dateWindow);
            case "google" -> searchGoogle(query, limit);
            default -> searchDuckDuckGo(query, limit);
        };
        return new SearchProviderResult(results, List.of());
    }

    private List<SearchResult> deepSeekSearch(String query, int limit, String originalQuestion,
                                              ZoneId zone) throws Exception {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("query", query);
        payload.put("count", Math.max(1, Math.min(50, limit)));
        payload.put("need_content", false);
        if (StringUtils.isNotBlank(originalQuestion)) payload.put("ori_question", originalQuestion);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizeBaseUrl(deepSeekBaseUrl) + "/v1/tools/search"))
                .header("Authorization", "Bearer " + deepSeekApiKey)
                .header("Content-Type", "application/json")
                .timeout(searchTimeout(Duration.ofSeconds(8)))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("DeepSeek Search HTTP " + response.statusCode());
        }
        JsonNode root = MAPPER.readTree(response.body());
        List<SearchResult> results = new ArrayList<>();
        int position = 1;
        for (JsonNode item : root.path("results")) {
            String url = item.path("url").asText("").trim();
            if (StringUtils.isBlank(url)) continue;
            String description = StringUtils.defaultIfBlank(item.path("snippet").asText(""),
                    item.path("summary").asText(""));
            results.add(new SearchResult(item.path("title").asText(""), url, description,
                    position++, normalizePublicationDate(item.path("date").asText(""), zone)));
        }
        return results;
    }

    private String deepSeekFetch(String url) throws InterruptedException {
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("url", url);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(deepSeekBaseUrl) + "/v1/tools/fetch"))
                    .header("Authorization", "Bearer " + deepSeekApiKey)
                    .header("Content-Type", "application/json")
                    .timeout(extractionTimeout(Duration.ofSeconds(12)))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
            JsonNode page = MAPPER.readTree(response.body());
            String title = page.path("title").asText("");
            String content = page.path("content").asText("");
            if (StringUtils.isBlank(content)) return null;
            ObjectNode result = MAPPER.createObjectNode();
            result.put("success", true);
            result.put("url", url);
            result.put("title", title);
            result.put("extraction_backend", "deepseek_fetch");
            result.put("content", StringUtils.truncate(content, MAX_CONTENT_LENGTH));
            result.put("content_length", content.length());
            result.putNull("publication_date");
            result.put("publication_date_source", "unknown");
            result.put("date_verified", false);
            return MAPPER.writeValueAsString(result);
        } catch (ToolExecutionDeadline.Expired e) {
            throw e;
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return null;
        }
    }

    private Duration searchTimeout(Duration maximum) throws InterruptedException {
        ensureExtractionActive();
        ToolExecutionDeadline.Deadline deadline = ToolExecutionDeadline.current();
        return deadline == null ? maximum : Duration.ofMillis(Math.min(maximum.toMillis(), deadline.timeoutMillis(100)));
    }

    private static String normalizeBaseUrl(String raw) {
        String value = StringUtils.defaultIfBlank(raw, "https://api.deepseek.com").trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static List<SearchResult> usableSearchResults(List<SearchResult> results) {
        return results == null ? List.of() : results.stream()
                .filter(result -> result != null && StringUtils.isNotBlank(result.url()))
                .toList();
    }

    private static String withFallbackMetadata(String response, String primary, String backend) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(response);
            root.put("fallback_from", primary);
            root.put("search_backend", backend);
            return MAPPER.writeValueAsString(root);
        } catch (Exception ignored) { return response; }
    }

    record SearchProviderResult(List<SearchResult> results, List<SearchImage> images) { }

    /**
     * 抓取网页内容 — 对标 hermes-agent web_extract
     *
     * @param url 目标 URL
     * @return 页面内容（纯文本，截断至 MAX_CONTENT_LENGTH）
     */
    public String extract(String url) {
        log.info("Web抓取: url={}", redactSensitive(url));

        try {
            ensureExtractionActive();
            // SSRF 防护：与 http_get/http_post 共用同一套判定（NetworkGuard 会解析 DNS
            // 并逐个检查返回的地址），不再用"字符串前缀黑名单"——
            // 那种写法既漏 169.254.169.254（云元数据）与 [::1]，也拦不住 DNS rebinding，
            // 而 web_extract 属于 PLAN_SAFE 工具：最严格的只读模式下它无需任何批准即可调用。
            String blocked = networkGuard.validateUrl(url);
            if (Objects.nonNull(blocked)) {
                return buildErrorResponse("安全拦截: " + blocked);
            }

            // A provider failure is not proof that the original page is unavailable.
            // Try the already guarded local transport once, within the same tool budget.
            boolean providerFallback = "tavily".equals(resolveBackend());
            if (providerFallback) {
                String providerResult = extractViaTavily(url);
                ensureExtractionActive();
                if (successfulExtractionResponse(providerResult)) return providerResult;
                log.info("Tavily 未提取到原文，改用安全直抓: url={}", redactSensitive(url));
            }

            // 走 GuardedHttpClient：重定向每一跳都重新过 NetworkGuard，响应体有硬上限。
            // 原先用静态客户端 + Redirect.NORMAL，首个 URL 通过后一个 302 就能打到内网。
            GuardedHttpClient.Response response = guardedHttpClient.get(
                    url,
                    Map.of("User-Agent", "Mozilla/5.0 (compatible; MiniAgent/1.0)",
                            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
                    extractionTimeout(Duration.ofSeconds(15)),
                    4 * 1024 * 1024);

            if (response.statusCode() != 200) {
                String directFailure = "HTTP " + response.statusCode() + " - 无法访问该页面";
                String fallback = tryDeepSeekFetch(url, providerFallback ? "tavily" : "guarded_http");
                return fallback == null ? buildErrorResponse(directFailure) : fallback;
            }

            String html = response.bodyText();
            PageMetadata metadata = extractPageMetadata(html, response.finalUri().toString(), currentSearchZone());
            String title = metadata.title();
            String publicationDate = metadata.publicationDate();
            String text = htmlToText(html);

            if (text.length() > MAX_CONTENT_LENGTH) {
                text = text.substring(0, MAX_CONTENT_LENGTH) + "\n\n... (内容已截断，共 " + text.length() + " 字符)";
            }

            ObjectNode result = MAPPER.createObjectNode();
            result.put("success", true);
            result.put("url", url);
            result.put("extraction_backend", "guarded_http");
            if (providerFallback) result.put("fallback_from", "tavily");
            result.put("title", title);
            addThumbnail(result, metadata.thumbnailUrl(), metadata.sourceUrl(), "source_page_metadata");
            addPublicationDate(result, publicationDate, "source_page_metadata", publicationDate != null);
            addRequestedDatePolicy(result, publicationDate, publicationDate != null, SearchDateContext.current());
            result.put("content", text);
            result.put("content_length", text.length());
            String direct = MAPPER.writeValueAsString(result);
            String providerFallbackResult = tryDeepSeekFetchIfEmpty(url, text,
                    providerFallback ? "tavily" : "guarded_http");
            return providerFallbackResult == null ? direct : providerFallbackResult;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return buildErrorResponse("EXTRACTION_INTERRUPTED", "网页提取已中断，未启动后续请求");
        } catch (CancellationException e) {
            return buildErrorResponse("EXTRACTION_CANCELLED", "网页提取已取消，未启动后续请求");
        } catch (ToolExecutionDeadline.Expired e) {
            return buildErrorResponse("EXTRACTION_DEADLINE", "网页提取预算已耗尽，未启动后续请求");
        } catch (GuardedHttpClient.NetworkRejectedException e) {
            // Do not bypass a blocked redirect by asking a third party to fetch it.
            return buildErrorResponse("安全拦截: " + e.getMessage());
        } catch (Exception e) {
            log.error("Web抓取失败: url={} error={}", redactSensitive(url), e.getMessage());
            String fallback = tryDeepSeekFetch(url, "guarded_http");
            return fallback == null ? buildErrorResponse("抓取失败: " + e.getMessage()) : fallback;
        }
    }

    private String tryDeepSeekFetchIfEmpty(String url, String content, String previousBackend) {
        if (StringUtils.isNotBlank(content)) return null;
        return tryDeepSeekFetch(url, previousBackend);
    }

    /** Third-party fetching is opt-in and only runs after the guarded local/provider path failed. */
    private String tryDeepSeekFetch(String url, String previousBackend) {
        if (StringUtils.isBlank(deepSeekApiKey)) return null;
        String fallback;
        try {
            ensureExtractionActive();
            fallback = deepSeekFetch(url);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (CancellationException | ToolExecutionDeadline.Expired e) {
            return null;
        }
        if (!successfulExtractionResponse(fallback)) return null;
        try {
            ObjectNode result = (ObjectNode) MAPPER.readTree(fallback);
            result.put("fallback_from", previousBackend);
            result.put("extraction_backend", "deepseek_fetch");
            return MAPPER.writeValueAsString(result);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 使用 Tavily Extract API 抓取网页 — 对标 hermes-agent 的 tavily extract
     * 文档: https://docs.tavily.com/documentation/api-reference/extract
     */
    String extractViaTavily(String url) throws InterruptedException {
        try {
            String apiKey = resolveKey(tavilyApiKey, "TAVILY_API_KEY");

            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("api_key", apiKey);
            ArrayNode urls = MAPPER.createArrayNode();
            urls.add(url);
            payload.set("urls", urls);
            payload.put("extract_depth", "basic");

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.tavily.com/extract"))
                    .header("Content-Type", "application/json")
                    .timeout(extractionTimeout(Duration.ofSeconds(30)))
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = MAPPER.readTree(response.body());
            JsonNode results = root.path("results");

            if (results.isArray() && !results.isEmpty()) {
                JsonNode first = results.get(0);
                String content = first.path("raw_content").asText("");
                if (StringUtils.isBlank(content)) {
                    content = first.path("content").asText("");
                }
                if (StringUtils.isBlank(content)) return buildErrorResponse("Tavily Extract 未返回正文");
                String title = first.path("title").asText("");
                String providerDate = normalizePublicationDate(first.path("published_date").asText(""),
                        currentSearchZone());
                // This is the existing source-date fetch, also used for cover/title metadata.
                // Never fetch every search result or the image itself for thumbnails.
                PageMetadata pageMetadata = extractSourcePageMetadata(url);
                String pageDate = pageMetadata.publicationDate();
                if (StringUtils.isBlank(title)) title = pageMetadata.title();
                String publicationDate = pageDate == null ? providerDate : pageDate;
                String publicationDateSource = pageDate != null ? "source_page_metadata"
                        : providerDate != null ? "tavily_extract_metadata" : "unknown";

                if (content.length() > MAX_CONTENT_LENGTH) {
                    content = content.substring(0, MAX_CONTENT_LENGTH)
                            + "\n\n... (内容已截断，共 " + content.length() + " 字符)";
                }

                ObjectNode result = MAPPER.createObjectNode();
                result.put("success", true);
                result.put("url", url);
                result.put("title", title);
                String thumbnail = pageMetadata.thumbnailUrl();
                String thumbnailSourceUrl = pageMetadata.sourceUrl();
                String thumbnailSource = "source_page_metadata";
                if (thumbnail == null && (first.path("url").asText("").isBlank()
                        || sameSourceUrl(first.path("url").asText(), url))) {
                    thumbnail = resultThumbnail(first, url);
                    thumbnailSourceUrl = url;
                    thumbnailSource = "tavily_extract_metadata";
                }
                addThumbnail(result, thumbnail, thumbnailSourceUrl, thumbnailSource);
                addPublicationDate(result, publicationDate, publicationDateSource, pageDate != null);
                addRequestedDatePolicy(result, publicationDate, pageDate != null, SearchDateContext.current());
                result.put("content", content);
                result.put("content_length", content.length());
                return MAPPER.writeValueAsString(result);
            }

            return buildErrorResponse("Tavily Extract 未返回结果");
        } catch (InterruptedException e) {
            throw e;
        } catch (CancellationException | ToolExecutionDeadline.Expired e) {
            throw e;
        } catch (Exception e) {
            log.error("Tavily Extract 失败: url={} error={}", redactSensitive(url), e.getMessage());
            return buildErrorResponse("Tavily 抓取失败: " + e.getMessage());
        }
    }

    private static void ensureExtractionActive() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Extraction interrupted");
        ToolExecutionDeadline.Deadline deadline = ToolExecutionDeadline.current();
        if (deadline != null) deadline.timeoutMillis(100);
    }

    private static boolean successfulExtractionResponse(String response) {
        try {
            JsonNode result = MAPPER.readTree(response);
            return result != null && result.path("success").asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Duration extractionTimeout(Duration maximum) throws InterruptedException {
        ensureExtractionActive();
        ToolExecutionDeadline.Deadline deadline = ToolExecutionDeadline.current();
        return deadline == null ? maximum : Duration.ofMillis(Math.min(maximum.toMillis(), deadline.timeoutMillis(100)));
    }

    // ========== 搜索后端实现 ==========

    /**
     * Tavily Search API — 专为 AI Agent 设计的搜索服务
     * 文档: https://docs.tavily.com/documentation/api-reference/search
     * 认证: api_key 在 JSON body 中（非 Header）
     */
    private JsonNode searchTavily(String query, int limit, boolean includeImages) throws Exception {
        String apiKey = resolveKey(tavilyApiKey, "TAVILY_API_KEY");

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("api_key", apiKey);
        payload.put("query", query);
        payload.put("max_results", limit);
        payload.put("topic", "general");
        payload.put("search_depth", "basic");
        payload.put("include_answer", false);
        payload.put("include_raw_content", false);
        if (includeImages) {
            payload.put("include_images", true);
            payload.put("include_image_descriptions", true);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.tavily.com/search"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                .build();

        return executeTavilySearch(request);
    }

    /** Package seam to verify the real request and response mapping without calling a provider. */
    JsonNode executeTavilySearch(HttpRequest request) throws Exception {
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("Tavily Search HTTP " + response.statusCode());
        return MAPPER.readTree(response.body());
    }

    static List<SearchResult> mapTavilyResults(JsonNode root, ZoneId zone) {
        List<SearchResult> results = new ArrayList<>();
        int pos = 1;
        for (JsonNode item : root.path("results")) {
            String sourceUrl = item.path("url").asText("");
            String thumbnail = resultThumbnail(item, sourceUrl);
            String thumbnailSource = "tavily_result_metadata";
            if (thumbnail == null) {
                // Global image candidates are separate. Only a provider-explicit
                // source association may attach an image to this web result.
                for (JsonNode image : root.path("images")) {
                    if (image.isObject() && sameSourceUrl(image.path("source_url").asText(""), sourceUrl)) {
                        thumbnail = imageUrl(image, sourceUrl);
                        if (thumbnail != null) {
                            thumbnailSource = "tavily_source_association";
                            break;
                        }
                    }
                }
            }
            results.add(new SearchResult(
                    item.path("title").asText(""),
                    item.path("url").asText(""),
                    item.path("content").asText(""),
                    pos++,
                    normalizePublicationDate(item.path("published_date").asText(""), zone),
                    thumbnail, thumbnail == null ? null : thumbnailSource
            ));
        }
        return results;
    }

    static List<SearchImage> mapTavilyImages(JsonNode root) {
        List<SearchImage> images = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        int serializedChars = 2; // Array brackets, plus an actual serialized object/comma per candidate.
        for (JsonNode candidate : root.path("images")) {
            if (images.size() >= MAX_RESULTS) break;
            // Standalone candidates have no page base: never invent a host for relative paths.
            String rawUrl = candidate.isTextual() ? candidate.asText() : candidate.path("url").asText("");
            String url = safeThumbnailUrl(rawUrl, null);
            if (url == null || !seen.add(url)) continue;
            String source = candidate.isObject() ? candidate.path("source_url").asText("") : "";
            source = safeImageSourceUrl(source);
            SearchImage image = new SearchImage(url,
                    candidate.isObject() ? StringUtils.truncate(candidate.path("title").asText(""), 200) : "",
                    candidate.isObject() ? StringUtils.truncate(candidate.path("description").asText(""), 600) : "", source);
            int cost = imageCandidateJson(image).toString().length() + (images.isEmpty() ? 0 : 1);
            // Never truncate a URL or infer another image. A later, shorter candidate can still fit.
            if (serializedChars + cost > MAX_IMAGE_CANDIDATE_JSON_CHARS) continue;
            serializedChars += cost;
            images.add(image);
        }
        return images;
    }

    private static String safeImageSourceUrl(String source) {
        // This is provenance supplied by the provider, never an inferred source page.
        return StringUtils.isBlank(source) ? null : safeThumbnailUrl(source, null);
    }

    private static ObjectNode imageCandidateJson(SearchImage image) {
        ObjectNode candidate = MAPPER.createObjectNode();
        candidate.put("url", image.url());
        candidate.put("title", image.title());
        candidate.put("description", image.description());
        candidate.put("source_association", image.sourceUrl() == null ? "unassociated" : "provider_explicit");
        if (image.sourceUrl() != null) candidate.put("source_url", image.sourceUrl());
        return candidate;
    }

    /**
     * Bing Web Search API
     * 文档: https://learn.microsoft.com/en-us/bing/search-apis/bing-web-search/quickstarts
     */
    private List<SearchResult> searchBing(String query, int limit, DateWindow dateWindow) throws Exception {
        String apiKey = resolveKey(bingApiKey, "BING_API_KEY");
        String url = "https://api.bing.microsoft.com/v7.0/search?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&count=" + limit
                + "&mkt=zh-CN"
                + (dateWindow == null ? "" : "&freshness=" + URLEncoder.encode(
                        dateWindow.start() + ".." + dateWindow.end(), StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Ocp-Apim-Subscription-Key", apiKey)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = MAPPER.readTree(response.body());
        return mapBingResults(root, currentSearchZone());
    }

    static List<SearchResult> mapBingResults(JsonNode root, ZoneId zone) {
        List<SearchResult> results = new ArrayList<>();
        int pos = 1;
        for (JsonNode item : root.path("webPages").path("value")) {
            results.add(new SearchResult(
                    item.path("name").asText(""),
                    item.path("url").asText(""),
                    item.path("snippet").asText(""),
                    pos++,
                    normalizePublicationDate(item.path("datePublished").asText(""), zone),
                    resultThumbnail(item, item.path("url").asText("")), "bing_metadata"
            ));
        }
        return results;
    }

    /**
     * Google Custom Search API
     * 文档: https://developers.google.com/custom-search/v1/reference/rest/v1/cse/list
     */
    private List<SearchResult> searchGoogle(String query, int limit) throws Exception {
        String apiKey = resolveKey(googleApiKey, "GOOGLE_API_KEY");
        String cseId = resolveKey(googleCseId, "GOOGLE_CSE_ID");

        String url = "https://customsearch.googleapis.com/customsearch/v1"
                + "?key=" + apiKey
                + "&cx=" + cseId
                + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&num=" + Math.min(limit, 10);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = MAPPER.readTree(response.body());
        return mapGoogleResults(root, currentSearchZone());
    }

    static List<SearchResult> mapGoogleResults(JsonNode root, ZoneId zone) {
        List<SearchResult> results = new ArrayList<>();
        int pos = 1;
        for (JsonNode item : root.path("items")) {
            results.add(new SearchResult(
                    item.path("title").asText(""),
                    item.path("link").asText(""),
                    item.path("snippet").asText(""),
                    pos++,
                    googlePublishedDate(item.path("pagemap").path("metatags"), zone),
                    googleThumbnail(item), "google_pagemap"
            ));
        }
        return results;
    }

    /**
     * DuckDuckGo Instant Answer API（免费，无需密钥）
     * 注意：返回的是即时答案，不是网页搜索结果。作为 fallback 使用。
     */
    private List<SearchResult> searchDuckDuckGo(String query, int limit) throws Exception {
        // DuckDuckGo Instant Answer — 适合简单问答
        String url = "https://api.duckduckgo.com/?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&format=json&no_html=1&skip_disambig=1";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = MAPPER.readTree(response.body());
        return mapDuckDuckGoResults(root, query, limit);
    }

    static List<SearchResult> mapDuckDuckGoResults(JsonNode root, String query, int limit) {
        List<SearchResult> results = new ArrayList<>();
        int pos = 1;

        // AbstractText — 即时答案
        String abstractText = root.path("AbstractText").asText("");
        String abstractUrl = root.path("AbstractURL").asText("");
        if (!StringUtils.isBlank(abstractText) && !StringUtils.isBlank(abstractUrl)) {
            results.add(new SearchResult(
                    root.path("Heading").asText(query),
                    abstractUrl,
                    abstractText,
                    pos++,
                    null,
                    safeThumbnailUrl(root.path("Image").asText(""), "https://duckduckgo.com/"),
                    "duckduckgo_metadata"
            ));
        }

        // RelatedTopics — 相关主题
        JsonNode topics = root.path("RelatedTopics");
        for (JsonNode topic : topics) {
            if (pos > limit) {
                break;
            }
            String text = topic.path("Text").asText("");
            String topicUrl = topic.path("FirstURL").asText("");
            if (!StringUtils.isBlank(text) && !StringUtils.isBlank(topicUrl)) {
                results.add(new SearchResult(
                        extractFirstSentence(text),
                        topicUrl,
                        text,
                        pos++,
                        null,
                        safeThumbnailUrl(topic.path("Icon").path("URL").asText(""), "https://duckduckgo.com/"),
                        "duckduckgo_metadata"
                ));
            }
        }

        // 如果 DuckDuckGo 没返回结果，说明这个查询不适合即时答案
        if (results.isEmpty()) {
            results.add(new SearchResult(
                    "DuckDuckGo 即时答案未找到结果",
                    "",
                    "DuckDuckGo 免费 API 只支持即时答案，不支持完整网页搜索。"
                    + "建议配置 BING_API_KEY（免费3000次/月）获得完整搜索能力。"
                    + "前往 https://www.microsoft.com/en-us/bing/apis/bing-web-search-api 申请。",
                    0,
                    null
            ));
        }

        return results;
    }

    // ========== 后端选择 ==========

    private String resolveBackend() {
        // 1. 环境变量显式指定
        if (StringUtils.isNotBlank(configuredBackend)) {
            return configuredBackend.toLowerCase().trim();
        }
        // 2. 自动检测（优先级：tavily > bing > google > duckduckgo）
        if (StringUtils.isNotBlank(tavilyApiKey)) {
            return "tavily";
        }
        if (StringUtils.isNotBlank(bingApiKey)) {
            return "bing";
        }
        if (StringUtils.isNotBlank(googleApiKey) && StringUtils.isNotBlank(googleCseId)) {
            return "google";
        }
        return "duckduckgo"; // 免费 fallback
    }

    /** The trusted user-request window survives query rewrites and cannot be broadened by model arguments. */
    static DateWindow resolveDateWindow(String query, String dateFrom, String dateTo, LocalDate today,
                                        SearchDateContext.Window requestedWindow) {
        DateWindow modelWindow = resolveDateWindow(query, dateFrom, dateTo, today);
        if (requestedWindow == null) return modelWindow;
        if (modelWindow == null) return new DateWindow(requestedWindow.start(), requestedWindow.end());

        LocalDate start = modelWindow.start().isBefore(requestedWindow.start())
                ? requestedWindow.start() : modelWindow.start();
        LocalDate end = modelWindow.end().isAfter(requestedWindow.end())
                ? requestedWindow.end() : modelWindow.end();
        if (start.isAfter(end)) {
            throw new DateWindowConflictException("搜索日期 " + modelWindow + " 与用户要求的 "
                    + requestedWindow.start() + ".." + requestedWindow.end()
                    + " 无交集。请按用户指定日期重新搜索，不得扩大为过去 24/48 小时或用旧消息凑数。");
        }
        return new DateWindow(start, end);
    }

    static DateWindow resolveDateWindow(String query, String dateFrom, String dateTo, LocalDate today) {
        LocalDate start = parseWindowDate(dateFrom, "dateFrom");
        LocalDate end = parseWindowDate(dateTo, "dateTo");
        if (start != null || end != null) {
            if (start == null) start = end;
            if (end == null) end = start;
            if (start.isAfter(end)) throw new IllegalArgumentException("web_search 的 dateFrom 不能晚于 dateTo");
            return new DateWindow(start, end);
        }

        String text = Objects.toString(query, "");
        Matcher dates = ISO_DATE_IN_QUERY.matcher(text);
        List<LocalDate> explicitDates = new ArrayList<>();
        while (dates.find()) {
            try { explicitDates.add(LocalDate.parse(dates.group(1))); }
            catch (DateTimeParseException ignored) { /* 不是有效日期，忽略 */ }
        }
        if (!explicitDates.isEmpty()) {
            return new DateWindow(explicitDates.get(0), explicitDates.get(explicitDates.size() - 1));
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("昨天") || lower.contains("昨日") || lower.contains("yesterday")) {
            LocalDate yesterday = today.minusDays(1);
            return new DateWindow(yesterday, yesterday);
        }
        if (lower.contains("今天") || lower.contains("今日") || lower.contains("today")) {
            return new DateWindow(today, today);
        }
        return null;
    }

    static String normalizePublicationDate(String value) {
        return normalizePublicationDate(value, ZoneId.of("Asia/Shanghai"));
    }

    static String normalizePublicationDate(String value, ZoneId zone) {
        if (StringUtils.isBlank(value)) return null;
        String text = value.trim();
        try { return OffsetDateTime.parse(text, DateTimeFormatter.ISO_DATE_TIME)
                .atZoneSameInstant(zone).toLocalDate().toString(); }
        catch (DateTimeParseException ignored) { }
        try { return ZonedDateTime.parse(text, DateTimeFormatter.ISO_DATE_TIME)
                .withZoneSameInstant(zone).toLocalDate().toString(); }
        catch (DateTimeParseException ignored) { }
        try { return ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
                .withZoneSameInstant(zone).toLocalDate().toString(); }
        catch (DateTimeParseException ignored) { }
        Matcher token = DATE_TOKEN.matcher(text);
        if (token.find()) {
            try {
                return LocalDate.of(Integer.parseInt(token.group(1)), Integer.parseInt(token.group(2)),
                        Integer.parseInt(token.group(3))).toString();
            } catch (RuntimeException ignored) { return null; }
        }
        try { return LocalDate.parse(text).toString(); }
        catch (DateTimeParseException ignored) { }
        return null;
    }

    static String extractPublishedDate(String html) {
        return extractPublishedDate(html, ZoneId.of("Asia/Shanghai"));
    }

    static String extractPublishedDate(String html, ZoneId zone) {
        if (StringUtils.isBlank(html)) return null;
        Matcher tags = META_TAG.matcher(html);
        while (tags.find()) {
            Map<String, String> attributes = new java.util.HashMap<>();
            Matcher attrs = HTML_ATTRIBUTE.matcher(tags.group());
            while (attrs.find()) attributes.put(attrs.group(1).toLowerCase(java.util.Locale.ROOT), attrs.group(3));
            String key = attributes.getOrDefault("property", attributes.getOrDefault("name",
                    attributes.getOrDefault("itemprop", ""))).trim();
            if (PUBLISH_DATE_KEY.matcher(key).matches()) {
                String date = normalizePublicationDate(attributes.get("content"), zone);
                if (date != null) return date;
            }
        }

        Matcher scripts = JSON_LD.matcher(html);
        while (scripts.find()) {
            try {
                String date = findJsonPublishedDate(MAPPER.readTree(scripts.group(2)), zone);
                if (date != null) return date;
            } catch (Exception ignored) { /* malformed JSON-LD is not publication evidence */ }
        }
        return null;
    }

    private static String findJsonPublishedDate(JsonNode node, ZoneId zone) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isObject()) {
            JsonNode published = node.get("datePublished");
            if (published != null) {
                String normalized = normalizePublicationDate(published.asText(""), zone);
                if (normalized != null) return normalized;
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                String date = findJsonPublishedDate(fields.next().getValue(), zone);
                if (date != null) return date;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                String date = findJsonPublishedDate(child, zone);
                if (date != null) return date;
            }
        }
        return null;
    }

    private static String googlePublishedDate(JsonNode metaTags, ZoneId zone) {
        if (!metaTags.isArray()) return null;
        for (JsonNode meta : metaTags) {
            var fields = meta.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (PUBLISH_DATE_KEY.matcher(field.getKey()).matches()) {
                    String date = normalizePublicationDate(field.getValue().asText(""), zone);
                    if (date != null) return date;
                }
            }
        }
        return null;
    }

    private static LocalDate parseWindowDate(String value, String field) {
        if (StringUtils.isBlank(value)) return null;
        try { return LocalDate.parse(value.trim()); }
        catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " 必须使用 YYYY-MM-DD 格式");
        }
    }

    static List<SearchResult> filterDateWindow(List<SearchResult> results, DateWindow window, int limit) {
        return results.stream()
                .filter(result -> !isOutsideWindow(result.publishedDate(), window))
                .limit(limit)
                .toList();
    }

    private static boolean isOutsideWindow(String publicationDate, DateWindow window) {
        if (publicationDate == null) return false;
        try {
            LocalDate date = LocalDate.parse(publicationDate);
            return date.isBefore(window.start()) || date.isAfter(window.end());
        } catch (DateTimeParseException ignored) { return false; }
    }

    static String withDateWindow(String query, DateWindow window) {
        LocalDate after = window.start().minusDays(1);
        LocalDate before = window.end().plusDays(1);
        return SEARCH_DATE_OPERATOR.matcher(Objects.toString(query, "")).replaceAll(" ").strip()
                + " after:" + after + " before:" + before
                + " 发布日期 " + window.start() + " 至 " + window.end();
    }

    private static ZoneId resolveSearchZone(String configuredZone) {
        return SearchDateContext.resolveZone(configuredZone);
    }

    private ZoneId currentSearchZone() {
        SearchDateContext.Window requestedWindow = SearchDateContext.current();
        return requestedWindow == null ? resolveSearchZone(configuredTimeZone) : requestedWindow.zone();
    }

    PageMetadata extractSourcePageMetadata(String url) {
        try {
            ensureExtractionActive();
            String blocked = networkGuard.validateUrl(url);
            if (blocked != null) return PageMetadata.empty(url);
            GuardedHttpClient.Response response = guardedHttpClient.get(
                    url,
                    Map.of("User-Agent", "Mozilla/5.0 (compatible; MiniAgent/1.0)",
                            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
                    extractionTimeout(Duration.ofSeconds(12)),
                    2 * 1024 * 1024);
            if (response.statusCode() != 200) return PageMetadata.empty(url);
            return extractPageMetadata(response.bodyText(), response.finalUri().toString(), currentSearchZone());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PageMetadata.empty(url);
        } catch (CancellationException | ToolExecutionDeadline.Expired e) {
            throw e;
        } catch (Exception e) {
            log.debug("无法从原文页面读取元数据: {}", e.getMessage());
            return PageMetadata.empty(url);
        }
    }

    private String resolveKey(String value, String envName) {
        // 先查 @Value 注入的值，再查系统环境变量
        if (StringUtils.isNotBlank(value)) {
            return value;
        }
        String envVal = System.getenv(envName);
        if (StringUtils.isNotBlank(envVal)) {
            return envVal;
        }
        throw new IllegalStateException("未配置 " + envName + "，无法使用该搜索后端");
    }

    private static String redactSensitive(String s) {
        if (Objects.isNull(s)) {
            return "";
        }
        return s
                .replaceAll("(?i)(access_token=)[^&\\s\"'}]+", "$1***")
                .replaceAll("(?i)(secret=)[^&\\s\"'}]+", "$1***")
                .replaceAll("(?i)(api[_-]?key=)[^&\\s\"'}]+", "$1***")
                .replaceAll("(?i)(key=)[^&\\s\"'}]+", "$1***");
    }

    // ========== 工具方法 ==========

    static String buildSuccessResponse(List<SearchResult> results, DateWindow dateWindow, ZoneId zone) throws Exception {
        return buildSuccessResponse(results, dateWindow, zone, List.of());
    }

    static String buildSuccessResponse(List<SearchResult> results, DateWindow dateWindow, ZoneId zone,
                                       List<SearchImage> images) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("success", true);
        if (dateWindow != null) {
            root.put("date_window", dateWindow.start() + ".." + dateWindow.end());
            root.put("time_zone", zone.getId());
            root.put("date_policy", "日期窗口是必须遵守的范围，不得自行扩大为过去 24/48 小时，不得用旧消息凑数。超出窗口的已知发布日期已过滤；搜索服务日期仅用于检索筛选，所有候选必须用 web_extract 核验原文发布日期在请求窗口内后才能引用。若原文返回 citation_eligible=false，则不得作为窗口内消息引用。合格消息不足时应明确说明，不得补入旧闻。");
        }

        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode webArray = MAPPER.createArrayNode();

        for (SearchResult r : results) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("title", r.title());
            item.put("url", r.url());
            item.put("description", r.description());
            item.put("position", r.position());
            addThumbnail(item, r.thumbnailUrl(), r.url(), r.thumbnailSource());
            item.put("publication_date", r.publishedDate() == null ? "unknown" : r.publishedDate());
            item.put("publication_date_source", r.publishedDate() == null ? "unknown" : "search_provider_metadata");
            item.put("date_verified", false);
            if (dateWindow != null) {
                item.put("within_requested_window", isWithinWindow(r.publishedDate(), dateWindow));
                item.put("citation_eligible", false);
            }
            webArray.add(item);
        }

        if (!images.isEmpty()) {
            ArrayNode imageArray = data.putArray("images");
            for (SearchImage image : images) {
                imageArray.add(imageCandidateJson(image));
            }
            data.put("images_policy", "图片是独立候选，不按序号或标题绑定网页结果。source_association=unassociated 表示搜索服务未提供来源关联；须核对图片标题、描述与具体作品及版本，证据不足不作封面，不把候选图当作网页事实或日期依据。");
        }
        data.set("web", webArray);
        root.set("data", data);
        return MAPPER.writeValueAsString(root);
    }

    private static String buildErrorResponse(String message) {
        return buildErrorResponse(null, message);
    }

    private static String buildErrorResponse(String code, String message) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("success", false);
            root.put("error", message);
            if (code != null) root.put("error_code", code);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }

    private static void addPublicationDate(ObjectNode target, String publicationDate, String source, boolean verified) {
        if (publicationDate == null) target.putNull("publication_date");
        else target.put("publication_date", publicationDate);
        target.put("publication_date_source", source);
        target.put("date_verified", verified);
    }

    private static void addRequestedDatePolicy(ObjectNode target, String publicationDate, boolean verified,
                                                SearchDateContext.Window requestedWindow) {
        if (requestedWindow == null) return;
        DateWindow window = new DateWindow(requestedWindow.start(), requestedWindow.end());
        boolean withinWindow = isWithinWindow(publicationDate, window);
        target.put("requested_date_window", window.toString());
        target.put("time_zone", requestedWindow.zone().getId());
        target.put("within_requested_window", withinWindow);
        target.put("citation_eligible", verified && withinWindow);
        target.put("date_policy", "仅 citation_eligible=true 的原文可作为请求日期内的消息引用；发布日期未知或超出日期窗口的内容不可用于凑数。");
    }

    private static boolean isWithinWindow(String publicationDate, DateWindow window) {
        if (publicationDate == null) return false;
        try {
            LocalDate date = LocalDate.parse(publicationDate);
            return !date.isBefore(window.start()) && !date.isAfter(window.end());
        } catch (DateTimeParseException ignored) { return false; }
    }

    private static final class DateWindowConflictException extends IllegalArgumentException {
        private DateWindowConflictException(String message) { super(message); }
    }

    /** Provider-owned result metadata only; a page's image stays attached to that page. */
    private static String resultThumbnail(JsonNode item, String sourceUrl) {
        for (String key : List.of("thumbnail_url", "thumbnailUrl", "thumbnail", "image_url", "imageUrl", "image")) {
            String image = imageUrl(item.path(key), sourceUrl);
            if (image != null) return image;
        }
        return null;
    }

    private static String googleThumbnail(JsonNode item) {
        String sourceUrl = item.path("link").asText("");
        JsonNode pageMap = item.path("pagemap");
        for (String key : List.of("cse_thumbnail", "cse_image")) {
            String image = imageUrl(pageMap.path(key), sourceUrl);
            if (image != null) return image;
        }
        for (JsonNode meta : pageMap.path("metatags")) {
            for (String key : List.of("og:image:secure_url", "og:image", "twitter:image", "twitter:image:src")) {
                String image = imageUrl(meta.path(key), sourceUrl);
                if (image != null) return image;
            }
        }
        return resultThumbnail(item, sourceUrl);
    }

    private static String imageUrl(JsonNode value, String sourceUrl) {
        return imageUrl(value, sourceUrl, 0);
    }

    private static String imageUrl(JsonNode value, String sourceUrl, int depth) {
        if (value == null || value.isNull() || value.isMissingNode() || depth > 5) return null;
        if (value.isTextual()) return safeThumbnailUrl(value.asText(), sourceUrl);
        if (value.isArray()) {
            for (JsonNode candidate : value) {
                String image = imageUrl(candidate, sourceUrl, depth + 1);
                if (image != null) return image;
            }
        } else if (value.isObject()) {
            String declaredSource = value.path("source_url").asText("");
            if (!declaredSource.isBlank() && !sameSourceUrl(declaredSource, sourceUrl)) return null;
            for (String key : List.of("thumbnail_url", "thumbnailUrl", "contentUrl", "url", "src")) {
                String image = imageUrl(value.path(key), sourceUrl, depth + 1);
                if (image != null) return image;
            }
        }
        return null;
    }

    /**
     * A thumbnail is a remote presentation hint, never a backend fetch target here.
     * Do not call validateUrl for each result: it resolves DNS and would turn a
     * provider response into N additional lookups. Actual page fetches still use
     * NetworkGuard + GuardedHttpClient, including redirect validation.
     */
    static String safeThumbnailUrl(String value, String sourceUrl) {
        if (StringUtils.isBlank(value) || value.length() > 4096) return null;
        try {
            String text = value.trim();
            if (text.startsWith("#") || text.startsWith("?")
                    || text.chars().anyMatch(c -> Character.isISOControl(c) || c == '\\')) return null;
            URI candidate = URI.create(text);
            if (!candidate.isAbsolute()) {
                if (StringUtils.isBlank(sourceUrl)) return null;
                URI base = URI.create(sourceUrl);
                if (!isHttpUrl(base)) return null;
                candidate = base.resolve(candidate);
            }
            // The chat CSP permits remote images over HTTPS only. Do not rewrite
            // an HTTP image to HTTPS: that would invent a different resource.
            if (!isHttpUrl(candidate) || !"https".equalsIgnoreCase(candidate.getScheme())
                    || candidate.getRawUserInfo() != null) return null;
            int port = candidate.getPort();
            if (port != -1 && port != 80 && port != 443) return null;
            String host = candidate.getHost();
            if (NetworkGuard.isBlockedHost(host) || !isPublicThumbnailHost(host)) return null;
            // Fragment identifiers are presentation state, not part of an image request.
            String normalized = candidate.normalize().toASCIIString();
            int fragment = normalized.indexOf('#');
            return fragment < 0 ? normalized : normalized.substring(0, fragment);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isHttpUrl(URI uri) {
        return uri.getHost() != null && ("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()));
    }

    private static boolean isPublicThumbnailHost(String host) {
        // Conservatively omit IP-literal thumbnails. This also rejects legacy
        // decimal/octal/hex IPv4 forms that browsers may canonicalize to loopback.
        if (host == null || host.indexOf(':') >= 0 || host.endsWith(".")) return false;
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches("(?:0x[0-9a-f]+|[0-9]+)(?:\\.(?:0x[0-9a-f]+|[0-9]+))*")) return false;
        return lower.contains(".") && lower.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+");
    }

    private static boolean sameSourceUrl(String left, String right) {
        String a = comparableSourceUrl(left);
        return a != null && a.equals(comparableSourceUrl(right));
    }

    private static String comparableSourceUrl(String value) {
        if (StringUtils.isBlank(value)) return null;
        try {
            URI uri = URI.create(value.trim()).normalize();
            if (!isHttpUrl(uri) || uri.getRawUserInfo() != null) return null;
            String scheme = uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            int port = uri.getPort();
            String authority = uri.getHost().toLowerCase(java.util.Locale.ROOT)
                    + (port == -1 || port == (scheme.equals("https") ? 443 : 80) ? "" : ":" + port);
            return scheme + "://" + authority + (StringUtils.isEmpty(uri.getRawPath()) ? "/" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static void addThumbnail(ObjectNode target, String thumbnail, String sourceUrl, String source) {
        String safeUrl = safeThumbnailUrl(thumbnail, sourceUrl);
        if (safeUrl == null || comparableSourceUrl(sourceUrl) == null) return;
        target.put("thumbnail_url", safeUrl);
        target.put("thumbnail_source_url", sourceUrl);
        target.put("thumbnail_source", StringUtils.defaultIfBlank(source, "search_provider_metadata"));
    }

    public record PageMetadata(String title, String publicationDate, String thumbnailUrl, String sourceUrl) {
        static PageMetadata empty(String url) { return new PageMetadata("", null, null, url); }
    }

    public static PageMetadata extractPageMetadata(String html, String sourceUrl) {
        SearchDateContext.Window window = SearchDateContext.current();
        return extractPageMetadata(html, sourceUrl, window == null ? ZoneId.of("Asia/Shanghai") : window.zone());
    }

    public static PageMetadata extractPageMetadata(String html, String sourceUrl, ZoneId zone) {
        if (StringUtils.isBlank(html)) return PageMetadata.empty(sourceUrl);
        Map<String, List<String>> metadata = new java.util.LinkedHashMap<>();
        Matcher tags = META_TAG.matcher(html);
        while (tags.find()) {
            Map<String, String> attributes = new java.util.HashMap<>();
            Matcher attrs = HTML_ATTRIBUTE.matcher(tags.group());
            while (attrs.find()) attributes.put(attrs.group(1).toLowerCase(java.util.Locale.ROOT), attrs.group(3));
            String key = attributes.getOrDefault("property", attributes.getOrDefault("name", ""))
                    .trim().toLowerCase(java.util.Locale.ROOT);
            String content = attributes.get("content");
            if (StringUtils.isNotBlank(content)) metadata.computeIfAbsent(key, unused -> new ArrayList<>()).add(content);
        }
        String title = extractTitle(html);
        for (String key : List.of("og:title", "twitter:title")) {
            if (title.isBlank()) title = cleanMetadataTitle(metadata.getOrDefault(key, List.of("")).get(0));
        }
        String thumbnail = null;
        for (String key : List.of("og:image:secure_url", "og:image", "og:image:url", "twitter:image", "twitter:image:src")) {
            for (String value : metadata.getOrDefault(key, List.of())) {
                thumbnail = safeThumbnailUrl(decodeHtmlEntities(value), sourceUrl);
                if (thumbnail != null) break;
            }
            if (thumbnail != null) break;
        }
        Matcher scripts = JSON_LD.matcher(html);
        while ((thumbnail == null || title.isBlank()) && scripts.find()) {
            try {
                List<JsonNode> pages = new ArrayList<>();
                collectJsonPageEntities(MAPPER.readTree(scripts.group(2)), 0, pages);
                for (JsonNode page : pages) {
                    if (thumbnail == null) thumbnail = imageUrl(page.path("image"), sourceUrl);
                    if (thumbnail == null) thumbnail = imageUrl(page.path("thumbnailUrl"), sourceUrl);
                    if (title.isBlank()) title = cleanMetadataTitle(page.path("headline").asText(page.path("name").asText("")));
                }
            } catch (Exception ignored) { /* malformed JSON-LD is not image/title evidence */ }
        }
        return new PageMetadata(title, extractPublishedDate(html, zone), thumbnail, sourceUrl);
    }

    private static void collectJsonPageEntities(JsonNode node, int depth, List<JsonNode> pages) {
        if (node == null || depth > 8) return;
        if (node.isArray()) {
            for (JsonNode child : node) collectJsonPageEntities(child, depth + 1, pages);
        } else if (node.isObject()) {
            // Do not accidentally use publisher/logo/person images from nested objects.
            boolean pageType = isImageContentType(node.path("@type"));
            if (pageType || (node.path("@type").isMissingNode() && node.hasNonNull("headline"))) pages.add(node);
            for (String key : List.of("@graph", "mainEntity")) {
                collectJsonPageEntities(node.path(key), depth + 1, pages);
            }
        }
    }

    private static boolean isImageContentType(JsonNode type) {
        if (type.isArray()) {
            for (JsonNode candidate : type) if (isImageContentType(candidate)) return true;
            return false;
        }
        if (!type.isTextual()) return false;
        String name = type.asText().toLowerCase(java.util.Locale.ROOT);
        int namespace = Math.max(name.lastIndexOf('/'), name.lastIndexOf('#'));
        return IMAGE_CONTENT_TYPES.contains(name.substring(namespace + 1));
    }

    private static String decodeHtmlEntities(String value) {
        Matcher entities = HTML_ENTITY.matcher(value);
        StringBuilder decoded = new StringBuilder();
        while (entities.find()) {
            String token = entities.group(1).toLowerCase(java.util.Locale.ROOT);
            String replacement = switch (token) {
                case "amp" -> "&";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "lt" -> "<";
                case "gt" -> ">";
                case "nbsp" -> " ";
                default -> {
                    try {
                        int codePoint = Integer.parseInt(token.substring(token.startsWith("#x") ? 2 : 1),
                                token.startsWith("#x") ? 16 : 10);
                        yield Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : entities.group();
                    } catch (IllegalArgumentException ignored) { yield entities.group(); }
                }
            };
            entities.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
        }
        return entities.appendTail(decoded).toString();
    }

    private static String cleanMetadataTitle(String title) {
        return title == null ? "" : decodeHtmlEntities(title).replaceAll("<[^>]+>", "")
                .replaceAll("\\s+", " ").trim();
    }

    /** 从 HTML 提取 <title> */
    private static String extractTitle(String html) {
        Matcher title = TITLE_TAG.matcher(html);
        return title.find() ? cleanMetadataTitle(title.group(1)) : "";
    }

    record DateWindow(LocalDate start, LocalDate end) {
        @Override public String toString() { return start + ".." + end; }
    }

    /** HTML → 纯文本（简易清洗） */
    private static String htmlToText(String html) {
        return html
                .replaceAll("(?is)<script[^>]*>.*?</script>", "")   // 移除 script
                .replaceAll("(?is)<style[^>]*>.*?</style>", "")    // 移除 style
                .replaceAll("(?is)<nav[^>]*>.*?</nav>", "")        // 移除 nav
                .replaceAll("(?is)<footer[^>]*>.*?</footer>", "")   // 移除 footer
                .replaceAll("(?is)<header[^>]*>.*?</header>", "")   // 移除 header
                .replaceAll("<br[^>]*>", "\n")                      // <br> → 换行
                .replaceAll("</?(p|div|h[1-6]|li|tr|blockquote)[^>]*>", "\n") // 块元素 → 换行
                .replaceAll("<[^>]+>", "")                          // 移除所有标签
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#39;", "'")
                .replaceAll("\\n{3,}", "\n\n")                      // 合并多余空行
                .replaceAll("[ \\t]{2,}", " ")                      // 合并多余空格
                .trim();
    }

    /** 提取第一句话作为标题 */
    private static String extractFirstSentence(String text) {
        int end = text.indexOf('.');
        if (end > 0 && end < 100) {
            return text.substring(0, end).trim();
        }
        if (text.length() > 80) {
            return text.substring(0, 80).trim() + "...";
        }
        return text;
    }

    /** 搜索结果记录 */
    record SearchImage(String url, String title, String description, String sourceUrl) { }

    record SearchResult(String title, String url, String description, int position, String publishedDate,
                        String thumbnailUrl, String thumbnailSource) {
        SearchResult(String title, String url, String description, int position, String publishedDate) {
            this(title, url, description, position, publishedDate, null, null);
        }
    }
}
