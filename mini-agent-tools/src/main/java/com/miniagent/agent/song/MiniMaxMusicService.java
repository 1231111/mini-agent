package com.miniagent.agent.song;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.impl.MiniMaxMusicParams;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * MiniMax 音乐生成。文档：
 * https://platform.minimaxi.com/docs/api-reference/music-generation
 * 写词：https://platform.minimax.io/docs/api-reference/lyrics-generation
 */
@Slf4j
@Service
public class MiniMaxMusicService {

    static final String DEFAULT_MODEL = "music-3.0";
    private static final String PATH_MUSIC = "/v1/music_generation";
    private static final String PATH_LYRICS = "/v1/lyrics_generation";
    private static final int LYRICS_TIMEOUT_SECONDS = 45;
    private static final int MUSIC_TIMEOUT_SECONDS = 150;
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 60;
    private static final int SNIPPET = 300;
    private static final int LYRICS_MAX = 3500;
    private static final int PROMPT_MAX = 2000;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Autowired
    private MediaStorage mediaStorage;

    @Value("${minimax.music.api-key:${MINIMAX_API_KEY:}}")
    private String apiKey;

    @Value("${minimax.music.base-url:https://api.minimax.cn}")
    private String baseUrl;

    @Value("${minimax.music.model:" + DEFAULT_MODEL + "}")
    private String model;

    public String generate(MiniMaxMusicParams params) {
        if (params == null) {
            return errorJson(ToolErrorCode.INVALID_ARGUMENT, "参数为空",
                    "请提供 prompt 或 lyrics");
        }
        if (StringUtils.isBlank(apiKey)) {
            return errorJson(ToolErrorCode.PERMISSION_DENIED, "MiniMax API Key 未配置",
                    "请在 config.yml 或环境变量设置 MINIMAX_API_KEY"
                            + "（开放平台接口密钥，不是 MIMO_API_KEY）");
        }
        boolean instrumental = Boolean.TRUE.equals(params.getInstrumental());
        String prompt = clip(params.getPrompt(), PROMPT_MAX);
        String lyrics = clip(params.getLyrics(), LYRICS_MAX);
        if (instrumental && StringUtils.isBlank(prompt)) {
            return errorJson(ToolErrorCode.INVALID_ARGUMENT, "纯音乐缺少风格描述",
                    "instrumental=true 时 prompt 必填");
        }
        if (!instrumental && StringUtils.isBlank(lyrics)) {
            if (StringUtils.isBlank(prompt)) {
                return errorJson(ToolErrorCode.INVALID_ARGUMENT, "缺少主题或歌词",
                        "prompt / lyrics 至少给一个");
            }
            lyrics = writeLyrics(prompt, params.getTitle());
        }
        return submitMusic(prompt, lyrics, params.getTitle(), instrumental);
    }

    private String writeLyrics(String prompt, String title) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("mode", "write_full_song");
        body.put("prompt", prompt);
        if (StringUtils.isNotBlank(title)) {
            body.put("title", title.trim());
        }
        try {
            JsonNode resp = post(PATH_LYRICS, body, LYRICS_TIMEOUT_SECONDS);
            int code = resp.path("base_resp").path("status_code").asInt(-1);
            if (code != 0) {
                log.warn("MiniMax 写词失败 code={} msg={}", code,
                        text(resp.path("base_resp"), "status_msg"));
                return "";
            }
            return text(resp, "lyrics");
        } catch (MiniMaxException e) {
            log.warn("MiniMax 写词请求失败: {}", e.getMessage());
            return "";
        }
    }

    private String submitMusic(String prompt, String lyrics, String title, boolean instrumental) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", StringUtils.defaultIfBlank(model, DEFAULT_MODEL).trim());
        body.put("output_format", "url");
        body.put("is_instrumental", instrumental);
        if (StringUtils.isNotBlank(prompt)) {
            body.put("prompt", prompt);
        }
        if (!instrumental && StringUtils.isNotBlank(lyrics)) {
            body.put("lyrics", lyrics);
        } else if (!instrumental) {
            body.put("lyrics_optimizer", true);
        }
        ObjectNode audio = MAPPER.createObjectNode();
        audio.put("sample_rate", 44100);
        audio.put("bitrate", 256000);
        audio.put("format", "mp3");
        body.set("audio_setting", audio);

        JsonNode resp;
        try {
            resp = post(PATH_MUSIC, body, MUSIC_TIMEOUT_SECONDS);
        } catch (MiniMaxException e) {
            return errorJson(e.code(), "MiniMax 生歌失败", e.getMessage());
        }
        int code = resp.path("base_resp").path("status_code").asInt(0);
        if (code != 0) {
            String msg = text(resp.path("base_resp"), "status_msg");
            return errorJson(errorCodeFor(code), "MiniMax 生歌失败",
                    "status_code=" + code + " " + msg);
        }
        JsonNode data = resp.path("data");
        int status = data.path("status").asInt(0);
        if (status == 1) {
            return errorJson(ToolErrorCode.DEPENDENCY_UNAVAILABLE, "歌曲仍在合成",
                    "同步接口返回合成中，请稍后用相同参数重试");
        }
        String audioField = text(data, "audio");
        if (StringUtils.isBlank(audioField)) {
            return errorJson(ToolErrorCode.EMPTY_RESULT, "响应没有音频",
                    snippet(resp));
        }
        byte[] bytes = audioField.startsWith("http")
                ? download(audioField) : decodeHex(audioField);
        if (bytes == null || bytes.length == 0) {
            if (audioField.startsWith("http")) {
                return "![生成的歌曲](" + audioField + ")";
            }
            return errorJson(ToolErrorCode.EMPTY_RESULT, "音频解码失败", snippet(resp));
        }
        String filename = "minimax_" + System.currentTimeMillis() + "_"
                + ThreadLocalRandom.current().nextInt(10000) + ".mp3";
        try {
            String url = mediaStorage.saveGenerated(bytes, filename);
            log.info("MiniMax 生歌已落盘: {}（{} 字节） title={}", url, bytes.length, title);
            return "![生成的歌曲](" + url + ")";
        } catch (IllegalStateException noOwner) {
            return errorJson(ToolErrorCode.INTERNAL_ERROR, "无法落盘：缺少登录用户上下文",
                    noOwner.getMessage());
        } catch (Exception e) {
            log.warn("MiniMax 落盘失败: {}", e.getMessage());
            if (audioField.startsWith("http")) {
                return "![生成的歌曲](" + audioField + ")";
            }
            return errorJson(ToolErrorCode.INTERNAL_ERROR, "落盘失败", e.getMessage());
        }
    }

    private JsonNode post(String path, ObjectNode body, int timeoutSeconds) {
        String root = StringUtils.removeEnd(baseUrl.trim(), "/");
        HttpRequest request = HttpRequest.newBuilder(URI.create(root + path))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MiniMaxException(ToolErrorCode.DEPENDENCY_UNAVAILABLE,
                    "网络异常：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MiniMaxException(ToolErrorCode.DEPENDENCY_UNAVAILABLE, "请求被中断");
        }
        if (response.statusCode() != 200) {
            throw new MiniMaxException(SongGenerationService.errorCodeFor(response.statusCode()),
                    "HTTP " + response.statusCode() + "：" + snippet(response.body()));
        }
        try {
            return MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new MiniMaxException(ToolErrorCode.DEPENDENCY_UNAVAILABLE,
                    "响应不是合法 JSON：" + snippet(response.body()));
        }
    }

    private byte[] download(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return null;
            }
            return response.body();
        } catch (Exception e) {
            log.warn("下载 MiniMax 音频失败: {}", e.getMessage());
            return null;
        }
    }

    static byte[] decodeHex(String hex) {
        if (hex == null) {
            return null;
        }
        String s = hex.trim();
        if (s.length() < 2 || (s.length() % 2) != 0) {
            return null;
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) + lo);
        }
        return out;
    }

    static ToolErrorCode errorCodeFor(int statusCode) {
        return switch (statusCode) {
            case 1002 -> ToolErrorCode.RATE_LIMITED;
            case 1004, 2049 -> ToolErrorCode.PERMISSION_DENIED;
            case 1008 -> ToolErrorCode.EXECUTION_FAILED;
            case 2013, 1026 -> ToolErrorCode.INVALID_ARGUMENT;
            default -> ToolErrorCode.EXECUTION_FAILED;
        };
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
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

    private static final class MiniMaxException extends RuntimeException {
        private final ToolErrorCode code;

        MiniMaxException(ToolErrorCode code, String message) {
            super(message);
            this.code = code == null ? ToolErrorCode.EXECUTION_FAILED : code;
        }

        ToolErrorCode code() {
            return code;
        }
    }
}
