package com.miniagent.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.agent.security.GuardedHttpClient;
import com.miniagent.agent.security.NetworkGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 *       "publication_date", "publication_date_source", "date_verified"}]}}
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
    private static final int MAX_CONTENT_LENGTH = 15000; // web_extract 单页最大字符数
    private static final Pattern META_TAG = Pattern.compile("(?is)<meta\\b[^>]*>");
    private static final Pattern HTML_ATTRIBUTE = Pattern.compile("(?is)([\\w:-]+)\\s*=\\s*(['\\\"])(.*?)\\2");
    private static final Pattern JSON_LD = Pattern.compile("(?is)<script\\b[^>]*type\\s*=\\s*(['\\\"])application/ld\\+json\\1[^>]*>(.*?)</script>");
    private static final Pattern DATE_TOKEN = Pattern.compile("(?<!\\d)(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?(?!\\d)");
    private static final Pattern ISO_DATE_IN_QUERY = Pattern.compile("(?<!\\d)(20\\d{2}-\\d{2}-\\d{2})(?!\\d)");
    private static final Pattern PUBLISH_DATE_KEY = Pattern.compile("(?i)^(article:published_time|og:published_time|datepublished|date|pubdate|parsely-pub-date|sailthru\\.date|dc\\.date\\.issued|citation_publication_date|published_time)$");

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
        if (Objects.isNull(limit) || limit <= 0) {
            limit = DEFAULT_LIMIT;
        }
        int requestedLimit = Math.min(limit, MAX_RESULTS);
        DateWindow dateWindow;
        try {
            dateWindow = resolveDateWindow(query, dateFrom, dateTo,
                    LocalDate.now(resolveSearchZone(configuredTimeZone)));
        } catch (IllegalArgumentException e) {
            return buildErrorResponse(e.getMessage());
        }
        String backend = resolveBackend();
        String searchQuery = dateWindow == null ? query : withDateWindow(query, dateWindow);
        int fetchLimit = dateWindow == null ? requestedLimit : Math.min(20, requestedLimit * 3);
        log.info("Web搜索: query='{}' limit={} backend={} dateWindow={}", query, requestedLimit, backend, dateWindow);

        try {
            List<SearchResult> results = switch (backend) {
                case "tavily" -> searchTavily(searchQuery, fetchLimit);
                case "bing" -> searchBing(searchQuery, fetchLimit, dateWindow);
                case "google" -> searchGoogle(searchQuery, fetchLimit);
                default -> searchDuckDuckGo(searchQuery, fetchLimit);
            };
            if (dateWindow != null) {
                results = filterDateWindow(results, dateWindow, requestedLimit);
            } else {
                results = results.stream().limit(requestedLimit).toList();
            }
            return buildSuccessResponse(results, dateWindow, resolveSearchZone(configuredTimeZone));
        } catch (Exception e) {
            log.error("Web搜索失败: backend={} error={}", backend, e.getMessage());
            return buildErrorResponse("搜索失败: " + e.getMessage() + "。请检查 " + backend + " API 密钥是否正确配置。");
        }
    }

    /**
     * 抓取网页内容 — 对标 hermes-agent web_extract
     *
     * @param url 目标 URL
     * @return 页面内容（纯文本，截断至 MAX_CONTENT_LENGTH）
     */
    public String extract(String url) {
        log.info("Web抓取: url={}", redactSensitive(url));

        // Tavily 后端使用 Tavily Extract API（更高效、自带内容清洗）
        String backend = resolveBackend();
        if ("tavily".equals(backend)) {
            return extractViaTavily(url);
        }

        try {
            // SSRF 防护：与 http_get/http_post 共用同一套判定（NetworkGuard 会解析 DNS
            // 并逐个检查返回的地址），不再用"字符串前缀黑名单"——
            // 那种写法既漏 169.254.169.254（云元数据）与 [::1]，也拦不住 DNS rebinding，
            // 而 web_extract 属于 PLAN_SAFE 工具：最严格的只读模式下它无需任何批准即可调用。
            String blocked = networkGuard.validateUrl(url);
            if (Objects.nonNull(blocked)) {
                return buildErrorResponse("安全拦截: " + blocked);
            }

            // 走 GuardedHttpClient：重定向每一跳都重新过 NetworkGuard，响应体有硬上限。
            // 原先用静态客户端 + Redirect.NORMAL，首个 URL 通过后一个 302 就能打到内网。
            GuardedHttpClient.Response response = guardedHttpClient.get(
                    url,
                    Map.of("User-Agent", "Mozilla/5.0 (compatible; MiniAgent/1.0)",
                            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
                    Duration.ofSeconds(15),
                    4 * 1024 * 1024);

            if (response.statusCode() != 200) {
                return buildErrorResponse("HTTP " + response.statusCode() + " - 无法访问该页面");
            }

            String html = response.bodyText();
            String title = extractTitle(html);
            String publicationDate = extractPublishedDate(html, resolveSearchZone(configuredTimeZone));
            String text = htmlToText(html);

            if (text.length() > MAX_CONTENT_LENGTH) {
                text = text.substring(0, MAX_CONTENT_LENGTH) + "\n\n... (内容已截断，共 " + text.length() + " 字符)";
            }

            ObjectNode result = MAPPER.createObjectNode();
            result.put("success", true);
            result.put("url", url);
            result.put("title", title);
            addPublicationDate(result, publicationDate, "source_page_metadata", publicationDate != null);
            result.put("content", text);
            result.put("content_length", text.length());
            return MAPPER.writeValueAsString(result);

        } catch (Exception e) {
            log.error("Web抓取失败: url={} error={}", redactSensitive(url), e.getMessage());
            return buildErrorResponse("抓取失败: " + e.getMessage());
        }
    }

    /**
     * 使用 Tavily Extract API 抓取网页 — 对标 hermes-agent 的 tavily extract
     * 文档: https://docs.tavily.com/documentation/api-reference/extract
     */
    private String extractViaTavily(String url) {
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
                    .timeout(Duration.ofSeconds(30))
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
                String title = first.path("title").asText("");
                String providerDate = normalizePublicationDate(first.path("published_date").asText(""),
                        resolveSearchZone(configuredTimeZone));
                String pageDate = extractSourcePublicationDate(url);
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
                addPublicationDate(result, publicationDate, publicationDateSource, pageDate != null);
                result.put("content", content);
                result.put("content_length", content.length());
                return MAPPER.writeValueAsString(result);
            }

            return buildErrorResponse("Tavily Extract 未返回结果");
        } catch (Exception e) {
            log.error("Tavily Extract 失败: url={} error={}", redactSensitive(url), e.getMessage());
            return buildErrorResponse("Tavily 抓取失败: " + e.getMessage());
        }
    }

    // ========== 搜索后端实现 ==========

    /**
     * Tavily Search API — 专为 AI Agent 设计的搜索服务
     * 文档: https://docs.tavily.com/documentation/api-reference/search
     * 认证: api_key 在 JSON body 中（非 Header）
     */
    private List<SearchResult> searchTavily(String query, int limit) throws Exception {
        String apiKey = resolveKey(tavilyApiKey, "TAVILY_API_KEY");

        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("api_key", apiKey);
        payload.put("query", query);
        payload.put("max_results", limit);
        payload.put("topic", "general");
        payload.put("search_depth", "basic");
        payload.put("include_answer", false);
        payload.put("include_raw_content", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.tavily.com/search"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = MAPPER.readTree(response.body());
        JsonNode resultsNode = root.path("results");

        List<SearchResult> results = new ArrayList<>();
        int pos = 1;
        for (JsonNode item : resultsNode) {
            results.add(new SearchResult(
                    item.path("title").asText(""),
                    item.path("url").asText(""),
                    item.path("content").asText(""),
                    pos++,
                    normalizePublicationDate(item.path("published_date").asText(""), resolveSearchZone(configuredTimeZone))
            ));
        }
        return results;
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
        JsonNode webPages = root.path("webPages").path("value");

        List<SearchResult> results = new ArrayList<>();
        int pos = 1;
        for (JsonNode item : webPages) {
            results.add(new SearchResult(
                    item.path("name").asText(""),
                    item.path("url").asText(""),
                    item.path("snippet").asText(""),
                    pos++,
                    normalizePublicationDate(item.path("datePublished").asText(""), resolveSearchZone(configuredTimeZone))
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
        JsonNode items = root.path("items");

        List<SearchResult> results = new ArrayList<>();
        int pos = 1;
        for (JsonNode item : items) {
            results.add(new SearchResult(
                    item.path("title").asText(""),
                    item.path("link").asText(""),
                    item.path("snippet").asText(""),
                    pos++,
                    googlePublishedDate(item.path("pagemap").path("metatags"), resolveSearchZone(configuredTimeZone))
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
                    null
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
                        null
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
        return Objects.toString(query, "").strip()
                + " after:" + after + " before:" + before
                + " 发布日期 " + window.start() + " 至 " + window.end();
    }

    private static ZoneId resolveSearchZone(String configuredZone) {
        try { return ZoneId.of(configuredZone); }
        catch (Exception ignored) { return ZoneId.systemDefault(); }
    }

    private String extractSourcePublicationDate(String url) {
        try {
            String blocked = networkGuard.validateUrl(url);
            if (blocked != null) return null;
            GuardedHttpClient.Response response = guardedHttpClient.get(
                    url,
                    Map.of("User-Agent", "Mozilla/5.0 (compatible; MiniAgent/1.0)",
                            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"),
                    Duration.ofSeconds(12),
                    2 * 1024 * 1024);
            if (response.statusCode() != 200) return null;
            return extractPublishedDate(response.bodyText(), resolveSearchZone(configuredTimeZone));
        } catch (Exception e) {
            log.debug("无法从原文页面读取发布日期: {}", e.getMessage());
            return null;
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
        ObjectNode root = MAPPER.createObjectNode();
        root.put("success", true);
        if (dateWindow != null) {
            root.put("date_window", dateWindow.start() + ".." + dateWindow.end());
            root.put("time_zone", zone.getId());
            root.put("date_policy", "超出窗口的已知发布日期已过滤；搜索服务日期仅用于检索筛选，所有候选必须用 web_extract 核验原文发布日期后才能引用。");
        }

        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode webArray = MAPPER.createArrayNode();

        for (SearchResult r : results) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("title", r.title());
            item.put("url", r.url());
            item.put("description", r.description());
            item.put("position", r.position());
            item.put("publication_date", r.publishedDate() == null ? "unknown" : r.publishedDate());
            item.put("publication_date_source", r.publishedDate() == null ? "unknown" : "search_provider_metadata");
            item.put("date_verified", false);
            webArray.add(item);
        }

        data.set("web", webArray);
        root.set("data", data);
        return MAPPER.writeValueAsString(root);
    }

    private static String buildErrorResponse(String message) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("success", false);
            root.put("error", message);
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

    /** 从 HTML 提取 <title> */
    private static String extractTitle(String html) {
        int start = html.indexOf("<title>");
        int end = html.indexOf("</title>");
        if (start >= 0 && end > start) {
            return html.substring(start + 7, end).trim()
                    .replaceAll("\\s+", " ")
                    .replaceAll("<[^>]+>", "");
        }
        return "";
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
    record SearchResult(String title, String url, String description, int position, String publishedDate) {}
}
