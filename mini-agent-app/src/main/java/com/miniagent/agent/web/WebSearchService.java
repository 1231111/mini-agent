package com.miniagent.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;

/**
 * 网络搜索服务 — 对标 hermes-agent 的 web_search / web_extract
 *
 * 架构：
 *   web_search → 后端路由（Bing / Google / DuckDuckGo）→ 标准化 JSON 结果
 *   web_extract → HttpClient 抓取页面 → 清洗提取纯文本/HTML
 *
 * 结果格式完全对标 hermes-agent：
 *   {"success": true, "data": {"web": [{"title", "url", "description", "position"}]}}
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
    private static final int MAX_CONTENT_LENGTH = 15000; // web_extract 单页最大字符数

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

    /**
     * 搜索网页 — 对标 hermes-agent web_search
     *
     * @param query 搜索关键词
     * @param limit 返回结果数量（默认5）
     * @return 标准化 JSON 结果
     */
    public String search(String query, Integer limit) {
        if (Objects.isNull(limit) || limit <= 0) limit = DEFAULT_LIMIT;
        String backend = resolveBackend();
        log.info("Web搜索: query='{}' limit={} backend={}", query, limit, backend);

        try {
            List<SearchResult> results = switch (backend) {
                case "tavily" -> searchTavily(query, limit);
                case "bing" -> searchBing(query, limit);
                case "google" -> searchGoogle(query, limit);
                default -> searchDuckDuckGo(query, limit);
            };

            return buildSuccessResponse(results);
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
            // SSRF 防护：拦截内网地址
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (Objects.isNull(host) || isPrivateHost(host)) {
                return buildErrorResponse("安全拦截: URL 目标是内网地址 (" + host + ")");
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("User-Agent", "Mozilla/5.0 (compatible; MiniAgent/1.0)")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return buildErrorResponse("HTTP " + response.statusCode() + " - 无法访问该页面");
            }

            String html = response.body();
            String title = extractTitle(html);
            String text = htmlToText(html);

            if (text.length() > MAX_CONTENT_LENGTH) {
                text = text.substring(0, MAX_CONTENT_LENGTH) + "\n\n... (内容已截断，共 " + text.length() + " 字符)";
            }

            ObjectNode result = MAPPER.createObjectNode();
            result.put("success", true);
            result.put("url", url);
            result.put("title", title);
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
                if (StringUtils.isBlank(content)) content = first.path("content").asText("");
                String title = first.path("title").asText("");

                if (content.length() > MAX_CONTENT_LENGTH) {
                    content = content.substring(0, MAX_CONTENT_LENGTH)
                            + "\n\n... (内容已截断，共 " + content.length() + " 字符)";
                }

                ObjectNode result = MAPPER.createObjectNode();
                result.put("success", true);
                result.put("url", url);
                result.put("title", title);
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
                    pos++
            ));
        }
        return results;
    }

    /**
     * Bing Web Search API
     * 文档: https://learn.microsoft.com/en-us/bing/search-apis/bing-web-search/quickstarts
     */
    private List<SearchResult> searchBing(String query, int limit) throws Exception {
        String apiKey = resolveKey(bingApiKey, "BING_API_KEY");
        String url = "https://api.bing.microsoft.com/v7.0/search?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&count=" + limit
                + "&mkt=zh-CN";

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
                    pos++
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
                + "&num=" + limit;

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
                    pos++
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
                    pos++
            ));
        }

        // RelatedTopics — 相关主题
        JsonNode topics = root.path("RelatedTopics");
        for (JsonNode topic : topics) {
            if (pos > limit) break;
            String text = topic.path("Text").asText("");
            String topicUrl = topic.path("FirstURL").asText("");
            if (!StringUtils.isBlank(text) && !StringUtils.isBlank(topicUrl)) {
                results.add(new SearchResult(
                        extractFirstSentence(text),
                        topicUrl,
                        text,
                        pos++
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
                    0
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
        if (StringUtils.isNotBlank(tavilyApiKey)) return "tavily";
        if (StringUtils.isNotBlank(bingApiKey)) return "bing";
        if (StringUtils.isNotBlank(googleApiKey) && StringUtils.isNotBlank(googleCseId)) return "google";
        return "duckduckgo"; // 免费 fallback
    }

    private String resolveKey(String value, String envName) {
        // 先查 @Value 注入的值，再查系统环境变量
        if (StringUtils.isNotBlank(value)) return value;
        String envVal = System.getenv(envName);
        if (StringUtils.isNotBlank(envVal)) return envVal;
        throw new IllegalStateException("未配置 " + envName + "，无法使用该搜索后端");
    }

    private static String redactSensitive(String s) {
        if (Objects.isNull(s)) return "";
        return s
                .replaceAll("(?i)(access_token=)[^&\\s\"'}]+", "$1***")
                .replaceAll("(?i)(secret=)[^&\\s\"'}]+", "$1***")
                .replaceAll("(?i)(api[_-]?key=)[^&\\s\"'}]+", "$1***")
                .replaceAll("(?i)(key=)[^&\\s\"'}]+", "$1***");
    }

    // ========== 工具方法 ==========

    private static String buildSuccessResponse(List<SearchResult> results) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("success", true);

        ObjectNode data = MAPPER.createObjectNode();
        ArrayNode webArray = MAPPER.createArrayNode();

        for (SearchResult r : results) {
            ObjectNode item = MAPPER.createObjectNode();
            item.put("title", r.title());
            item.put("url", r.url());
            item.put("description", r.description());
            item.put("position", r.position());
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
        if (end > 0 && end < 100) return text.substring(0, end).trim();
        if (text.length() > 80) return text.substring(0, 80).trim() + "...";
        return text;
    }

    /** SSRF 防护：拦截内网/本地地址 */
    private static boolean isPrivateHost(String host) {
        return host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.startsWith("10.")
                || host.startsWith("192.168.")
                || host.startsWith("172.16.") || host.startsWith("172.17.")
                || host.startsWith("172.18.") || host.startsWith("172.19.")
                || host.startsWith("172.2")   || host.startsWith("172.30.")
                || host.startsWith("172.31.")
                || host.equals("0.0.0.0")
                || host.endsWith(".local")
                || host.endsWith(".internal");
    }

    /** 搜索结果记录 */
    private record SearchResult(String title, String url, String description, int position) {}
}
