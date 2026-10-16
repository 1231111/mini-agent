package com.miniagent.agent.song;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.impl.LyriaMusicParams;
import com.miniagent.config.storage.MediaStorage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Google Lyria 3.5。文档：
 * https://ai.google.dev/gemini-api/docs/music-generation
 * POST /v1beta/interactions ，模型 lyria-3.5，音频在 steps[].content[].data（base64）。
 */
@Slf4j
@Service
public class LyriaMusicService {

    static final String DEFAULT_MODEL = "lyria-3.5";
    private static final String PATH = "/v1beta/interactions";
    private static final int HTTP_TIMEOUT_SECONDS = 230;
    private static final int SNIPPET = 300;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Autowired
    private MediaStorage mediaStorage;

    @Value("${lyria.music.api-key:${GEMINI_API_KEY:}}")
    private String apiKey;

    @Value("${lyria.music.base-url:https://generativelanguage.googleapis.com}")
    private String baseUrl;

    @Value("${lyria.music.model:" + DEFAULT_MODEL + "}")
    private String model;

    public String generate(LyriaMusicParams params) {
        if (params == null || StringUtils.isBlank(params.getPrompt())) {
            return errorJson(ToolErrorCode.INVALID_ARGUMENT, "缺少 prompt",
                    "请描述风格、情绪或主题");
        }
        if (StringUtils.isBlank(apiKey)) {
            return errorJson(ToolErrorCode.PERMISSION_DENIED, "Gemini API Key 未配置",
                    "请设置 GEMINI_API_KEY（Google AI Studio 的 key，不是 GOOGLE_API_KEY）");
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", StringUtils.defaultIfBlank(model, DEFAULT_MODEL).trim());
        body.put("input", buildInput(params));

        JsonNode resp;
        try {
            resp = post(body);
        } catch (LyriaException e) {
            return errorJson(e.code(), "Lyria 生歌失败", e.getMessage());
        }
        if (resp.has("error")) {
            int http = resp.path("error").path("code").asInt(400);
            String msg = text(resp.path("error"), "message");
            return errorJson(SongGenerationService.errorCodeFor(http), "Lyria 生歌失败", msg);
        }
        AudioHit hit = extract(resp);
        if (hit == null || StringUtils.isBlank(hit.base64())) {
            return errorJson(ToolErrorCode.EMPTY_RESULT, "响应没有音频", snippet(resp));
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(hit.base64());
        } catch (IllegalArgumentException e) {
            return errorJson(ToolErrorCode.EMPTY_RESULT, "音频不是合法 base64", snippet(resp));
        }
        String ext = hit.mime() != null && hit.mime().contains("wav") ? "wav" : "mp3";
        String filename = "lyria_" + System.currentTimeMillis() + "_"
                + ThreadLocalRandom.current().nextInt(10000) + "." + ext;
        try {
            String url = mediaStorage.saveGenerated(bytes, filename);
            log.info("Lyria 生歌已落盘: {}（{} 字节）", url, bytes.length);
            return "![生成的歌曲](" + url + ")";
        } catch (IllegalStateException noOwner) {
            return errorJson(ToolErrorCode.INTERNAL_ERROR, "无法落盘：缺少登录用户上下文",
                    noOwner.getMessage());
        } catch (Exception e) {
            return errorJson(ToolErrorCode.INTERNAL_ERROR, "落盘失败", e.getMessage());
        }
    }

    static String buildInput(LyriaMusicParams params) {
        StringBuilder sb = new StringBuilder();
        if (Boolean.TRUE.equals(params.getInstrumental())) {
            sb.append("Instrumental only, no vocals. ");
        }
        sb.append(params.getPrompt().trim());
        if (StringUtils.isNotBlank(params.getLyrics())) {
            sb.append("\n\nSing these lyrics:\n").append(params.getLyrics().trim());
        }
        return sb.toString();
    }

    /** 从 Interactions 响应里取出最后一块音频。 */
    static AudioHit extract(JsonNode root) {
        if (root == null) {
            return null;
        }
        AudioHit direct = extractFrom(root);
        if (direct != null) {
            return direct;
        }
        JsonNode nested = root.path("interaction");
        return nested.isObject() ? extractFrom(nested) : null;
    }

    private static AudioHit extractFrom(JsonNode root) {
        AudioHit found = null;
        JsonNode steps = root.path("steps");
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                if (!"model_output".equals(step.path("type").asText())) {
                    continue;
                }
                JsonNode content = step.path("content");
                if (!content.isArray()) {
                    continue;
                }
                for (JsonNode block : content) {
                    if (!"audio".equals(block.path("type").asText())) {
                        continue;
                    }
                    String data = text(block, "data");
                    if (StringUtils.isBlank(data)) {
                        continue;
                    }
                    String mime = text(block, "mime_type");
                    found = new AudioHit(data, StringUtils.defaultIfBlank(mime, "audio/mpeg"));
                }
            }
        }
        if (found != null) {
            return found;
        }
        JsonNode audio = root.path("output_audio");
        String data = text(audio, "data");
        if (StringUtils.isBlank(data)) {
            return null;
        }
        return new AudioHit(data, StringUtils.defaultIfBlank(text(audio, "mime_type"), "audio/mpeg"));
    }

    private JsonNode post(ObjectNode body) {
        String root = StringUtils.removeEnd(baseUrl.trim(), "/");
        HttpRequest request = HttpRequest.newBuilder(URI.create(root + PATH))
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new LyriaException(ToolErrorCode.DEPENDENCY_UNAVAILABLE, "网络异常：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LyriaException(ToolErrorCode.DEPENDENCY_UNAVAILABLE, "请求被中断");
        }
        if (response.statusCode() != 200) {
            throw new LyriaException(SongGenerationService.errorCodeFor(response.statusCode()),
                    "HTTP " + response.statusCode() + "：" + snippet(response.body()));
        }
        try {
            return MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new LyriaException(ToolErrorCode.DEPENDENCY_UNAVAILABLE,
                    "响应不是合法 JSON：" + snippet(response.body()));
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private static String snippet(JsonNode body) {
        return body == null ? "" : snippet(body.toString());
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= SNIPPET ? flat : flat.substring(0, SNIPPET) + "…";
    }

    private static String errorJson(ToolErrorCode code, String error, String hint) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("errorCode", (code == null ? ToolErrorCode.EXECUTION_FAILED : code).name());
        out.put("error", error);
        out.put("hint", hint);
        try {
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"error\":\"结果序列化失败\"}";
        }
    }

    record AudioHit(String base64, String mime) {
    }

    private static final class LyriaException extends RuntimeException {
        private final ToolErrorCode code;

        LyriaException(ToolErrorCode code, String message) {
            super(message);
            this.code = code == null ? ToolErrorCode.EXECUTION_FAILED : code;
        }

        ToolErrorCode code() {
            return code;
        }
    }
}
