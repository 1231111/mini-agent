package com.miniagent.agent.song;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniagent.agent.tool.impl.SongGenerateParams;
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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 生歌服务 —— 对标 {@code image_generate} / {@code comfyui_tts}，但走 SenseAudio 云端异步接口。
 *
 * <h2>为什么要有这个类（而不是让技能自己跑脚本）</h2>
 *
 * <p>生成物必须落到 {@code media/generated/&lt;owner&gt;/} 才能被 {@code /api/generated-media/**}
 * 交付给前端。那个 {@code <owner>} 来自 {@code MemoryStore.currentUserId}，是 {@code ThreadLocal}：
 * Java 侧靠 {@code RunScope.bind()} 把它带进工具执行线程，而 {@code exec_command} 起的是
 * <b>独立操作系统进程</b>，环境变量里没有 userId，ThreadLocal 也不可能跨进程。
 * 所以「生歌落盘」这件事只能在 Java 里做，技能层永远做不到 —— 这不是口味问题。
 *
 * <h2>为什么用有界轮询 + taskId 续查</h2>
 *
 * <p>{@code song_generate} 既不是只读、也不幂等、也不是 {@code exec_command}，
 * 外层闸门一旦先触发就会被判成「终态未知」并<b>中止整轮任务</b>
 * （见 {@code AgentLoop.timeoutToolResult}）。所以这里的铁律是：
 * <b>内部轮询预算必须小于外层闸门</b>，到点就正常返回 {@code status=PENDING} + {@code taskId}，
 * 让模型下一轮续查。宁可多花一轮对话，也不能让一次超时把整条任务打死。
 *
 * <p>接口契约（POST 提交 / GET 轮询，响应取 {@code response.data[0]}）与
 * {@code ~/.miniagent/skills/music-generation/scripts/senseaudio_song.py} 完全一致，
 * 两边的端点、方法、字段名必须同步改。
 */
@Slf4j
@Service
public class SongGenerationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 单次提交/查询的 HTTP 超时。慢的是「轮询总时长」，不是单次请求。 */
    private static final int HTTP_TIMEOUT_SECONDS = 30;
    /** 音频下载超时：一首歌几 MB，60s 足够。 */
    private static final int DOWNLOAD_TIMEOUT_SECONDS = 60;
    /** 轮询间隔。 */
    private static final long POLL_INTERVAL_MS = 5_000L;
    /** 歌词单独的子预算：歌词是纯文本生成，明显快于作曲，不该吃掉作曲的预算。 */
    private static final long LYRICS_BUDGET_MS = 90_000L;
    /** 写进错误信息里的响应体上限，避免把整页 HTML 灌进模型上下文。 */
    private static final int BODY_SNIPPET_CHARS = 300;

    @Autowired
    private MediaStorage mediaStorage;

    @Value("${song.gen.senseaudio-key:${SENSEAUDIO_API_KEY:}}")
    private String apiKey;

    @Value("${song.gen.base-url:https://api.senseaudio.cn}")
    private String baseUrl;

    @Value("${song.gen.model:sensesong}")
    private String songModel;

    @Value("${song.gen.lyrics-provider:sensesong}")
    private String lyricsProvider;

    /**
     * 生歌入口。返回给模型的内容只有三种形态：
     * <ul>
     *   <li>成功：{@code ![生成的歌曲](/api/generated-media/<owner>/song_xxx.mp3)}（markdown，可直接渲染）</li>
     *   <li>未完成：{@code {"status":"PENDING","taskId":...}}，带续查提示</li>
     *   <li>失败：{@code {"error":...}}，带人话原因和下一步</li>
     * </ul>
     */
    public String generate(SongGenerateParams params) {
        if (params == null) {
            return errorJson("参数为空", "请带上 prompt / lyrics / taskId 之一重试");
        }
        if (StringUtils.isBlank(apiKey)) {
            return errorJson("生歌 API Key 未配置",
                    "请配置 song.gen.senseaudio-key 或环境变量 SENSEAUDIO_API_KEY 后重试；"
                            + "不要伪造结果，直接告诉用户缺少密钥");
        }

        long deadline = System.currentTimeMillis() + SongGenerateParams.DEFAULT_POLL_BUDGET_SECONDS * 1000L;

        // 1) 续查已提交的歌曲任务：只查，绝不重新提交（重新提交会再花一次钱、还会多出一首歌）
        if (StringUtils.isNotBlank(params.getTaskId())) {
            return pollSong(params.getTaskId(), deadline);
        }

        boolean instrumental = Boolean.TRUE.equals(params.getInstrumental());

        // 2) 歌词：显式给了就用给的；纯音乐不需要歌词；否则先生成
        String lyrics = params.getLyrics();
        if (StringUtils.isBlank(lyrics) && StringUtils.isNotBlank(params.getLyricsTaskId())) {
            LyricsResult resumed = pollLyrics(params.getLyricsTaskId(), deadline);
            if (resumed.isPending()) return resumed.pendingJson();
            if (resumed.isFailed()) return resumed.errorJson();
            lyrics = resumed.text();
        }
        if (StringUtils.isBlank(lyrics) && !instrumental) {
            if (StringUtils.isBlank(params.getPrompt())) {
                return errorJson("缺少歌词来源",
                        "prompt / lyrics / taskId 至少给一个；纯音乐请显式传 instrumental=true");
            }
            LyricsResult created = createLyrics(params.getPrompt(), deadline);
            if (created.isPending()) return created.pendingJson();
            if (created.isFailed()) return created.errorJson();
            lyrics = created.text();
        }

        // 3) 提交作曲
        String taskId;
        try {
            taskId = submitSong(params, lyrics, instrumental);
        } catch (SongApiException e) {
            return errorJson("提交生歌任务失败", e.getMessage());
        }
        log.info("生歌已提交: task={} style={} instrumental={}", taskId,
                StringUtils.defaultIfBlank(params.getStyle(), "pop"), instrumental);

        // 4) 在内部预算内轮询到出结果，超出则交回模型续查
        return pollSong(taskId, deadline);
    }

    // ==================== 歌词 ====================

    private LyricsResult createLyrics(String prompt, long deadline) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("prompt", prompt);
        payload.put("provider", lyricsProvider);

        JsonNode body;
        try {
            body = postJson("/v1/song/lyrics/create", payload.toString());
        } catch (SongApiException e) {
            return LyricsResult.failed(errorJson("歌词生成请求失败", e.getMessage()));
        }

        // 文档同时描述了两种返回：异步给 task_id，同步给 data。两种都要能接。
        String taskId = textOf(body, "task_id");
        if (StringUtils.isNotBlank(taskId) && body.path("data").isMissingNode()) {
            return pollLyrics(taskId, Math.min(deadline, System.currentTimeMillis() + LYRICS_BUDGET_MS));
        }
        String text = textOf(firstElement(body.path("data")), "text");
        if (StringUtils.isBlank(text)) {
            return LyricsResult.failed(errorJson("歌词生成返回为空",
                    "换一个更具体的 prompt 重试；原始响应 " + snippet(body)));
        }
        return LyricsResult.of(text);
    }

    private LyricsResult pollLyrics(String taskId, long deadline) {
        while (true) {
            JsonNode body;
            try {
                body = getJson("/v1/song/lyrics/pending/" + taskId);
            } catch (SongApiException e) {
                return LyricsResult.failed(errorJson("查询歌词状态失败", e.getMessage()));
            }
            String status = textOf(body, "status").toUpperCase(Locale.ROOT);
            if ("SUCCESS".equals(status)) {
                String text = textOf(firstElement(body.path("response").path("data")), "text");
                if (StringUtils.isBlank(text)) {
                    return LyricsResult.failed(errorJson("歌词任务成功但正文为空",
                            "原始响应 " + snippet(body)));
                }
                return LyricsResult.of(text);
            }
            if ("FAILED".equals(status)) {
                return LyricsResult.failed(errorJson("歌词生成失败",
                        "换一个 prompt 重新提交；原始响应 " + snippet(body)));
            }
            if (System.currentTimeMillis() >= deadline) {
                return LyricsResult.pending(pendingLyricsJson(taskId));
            }
            if (!sleepQuietly()) {
                return LyricsResult.pending(pendingLyricsJson(taskId));
            }
        }
    }

    // ==================== 作曲 ====================

    private String submitSong(SongGenerateParams params, String lyrics, boolean instrumental)
            throws SongApiException {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("model", songModel);
        payload.put("style", StringUtils.defaultIfBlank(params.getStyle(), "pop"));
        payload.put("instrumental", instrumental);
        // 纯音乐不传歌词：接口把 lyrics 当可选字段，空串反而可能被当成"有词但空"
        if (StringUtils.isNotBlank(lyrics)) {
            payload.put("lyrics", lyrics);
        }
        if (!instrumental) {
            payload.put("vocal_gender", "m".equalsIgnoreCase(
                    StringUtils.defaultIfBlank(params.getVocal(), "f")) ? "m" : "f");
        }
        if (StringUtils.isNotBlank(params.getTitle())) {
            payload.put("title", params.getTitle());
        }

        JsonNode body = postJson("/v1/song/music/create", payload.toString());
        String taskId = textOf(body, "task_id");
        if (StringUtils.isBlank(taskId)) {
            throw new SongApiException("响应中没有 task_id：" + snippet(body));
        }
        return taskId;
    }

    private String pollSong(String taskId, long deadline) {
        while (true) {
            JsonNode body;
            try {
                body = getJson("/v1/song/music/pending/" + taskId);
            } catch (SongApiException e) {
                return errorJson("查询歌曲状态失败", e.getMessage() + "；taskId=" + taskId);
            }
            String status = textOf(body, "status").toUpperCase(Locale.ROOT);
            if ("SUCCESS".equals(status)) {
                return deliverSong(taskId, body);
            }
            if ("FAILED".equals(status)) {
                return errorJson("歌曲生成失败",
                        "换个 prompt / style 重新提交；taskId=" + taskId + "，原始响应 " + snippet(body));
            }
            if (System.currentTimeMillis() >= deadline) {
                return pendingSongJson(taskId);
            }
            if (!sleepQuietly()) {
                return pendingSongJson(taskId);
            }
        }
    }

    /**
     * 拿到 {@code audio_url} 后下载并落盘。
     *
     * <p>落盘失败时的降级是有意为之的：{@code audio_url} 是能直接播的 https 地址，
     * 前端对 https 是放行的。宁可给用户一个会过期的远端链接，也不要让他什么都没有；
     * 但<b>缺少 owner 上下文必须显性失败</b> —— {@code MediaStorage} 的设计就是拒绝无 owner 写入，
     * 这里不能悄悄换目录把问题藏起来。
     */
    private String deliverSong(String taskId, JsonNode body) {
        JsonNode song = firstElement(body.path("response").path("data"));
        String audioUrl = textOf(song, "audio_url");
        if (StringUtils.isBlank(audioUrl)) {
            return errorJson("任务成功但没有音频地址",
                    "audio_url 为空；taskId=" + taskId + "，原始响应 " + snippet(body));
        }

        byte[] audio = download(audioUrl);
        if (audio == null || audio.length == 0) {
            log.warn("生歌音频下载失败，回退远端链接: task={} url={}", taskId, audioUrl);
            return "![生成的歌曲](" + audioUrl + ")";
        }

        String filename = "song_" + System.currentTimeMillis() + "_"
                + ThreadLocalRandom.current().nextInt(10000) + ".mp3";
        String url;
        try {
            url = mediaStorage.saveGenerated(audio, filename);
        } catch (IllegalStateException noOwner) {
            return errorJson("无法落盘：缺少登录用户上下文", noOwner.getMessage());
        } catch (Exception e) {
            log.warn("生歌落盘失败，回退远端链接: {}", e.getMessage());
            return "![生成的歌曲](" + audioUrl + ")";
        }

        log.info("生歌已落盘: {}（{} 字节, task={}）", url, audio.length, taskId);
        return "![生成的歌曲](" + url + ")";
    }

    private byte[] download(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(DOWNLOAD_TIMEOUT_SECONDS))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.warn("下载音频返回 {}", response.statusCode());
                return null;
            }
            return response.body();
        } catch (Exception e) {
            log.warn("下载音频失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== HTTP ====================

    private JsonNode postJson(String path, String bodyJson) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8))
                .build());
    }

    private JsonNode getJson(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .build());
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new SongApiException("网络异常：" + e.getMessage());
        }
        if (response.statusCode() != 200) {
            throw new SongApiException("HTTP " + response.statusCode() + "：" + snippet(response.body()));
        }
        try {
            return MAPPER.readTree(response.body());
        } catch (Exception e) {
            throw new SongApiException("响应不是合法 JSON：" + snippet(response.body()));
        }
    }

    // ==================== 小工具 ====================

    /**
     * 睡一个轮询间隔。
     *
     * <p>返回 {@code false} 表示线程被中断（外层闸门取消调用）。这时必须立刻退出轮询：
     * 中断标志置上后 {@code Thread.sleep} 每次都立即抛异常，如果只是"吞掉中断继续循环"，
     * 就会变成一直到 deadline 的忙等空转。
     */
    private static boolean sleepQuietly() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static JsonNode firstElement(JsonNode array) {
        return array != null && array.isArray() && array.size() > 0 ? array.get(0) : null;
    }

    private static String textOf(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
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
        return flat.length() <= BODY_SNIPPET_CHARS ? flat : flat.substring(0, BODY_SNIPPET_CHARS) + "…";
    }

    private static String pendingSongJson(String taskId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "PENDING");
        out.put("taskId", taskId);
        out.put("hint", "歌曲仍在生成中（云端约需 3 分钟）。本轮不要重复提交；"
                + "先告诉用户还在生成，下一轮再调 song_generate 并把 taskId 原样传回来续查。");
        return toJson(out);
    }

    private static String pendingLyricsJson(String taskId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "PENDING");
        out.put("stage", "lyrics");
        out.put("lyricsTaskId", taskId);
        out.put("hint", "歌词仍在生成中。下一轮再调 song_generate，只传 lyricsTaskId 继续等待，不要重新提交。");
        return toJson(out);
    }

    private static String errorJson(String error, String hint) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", error);
        out.put("hint", hint);
        return toJson(out);
    }

    private static String toJson(Map<String, Object> out) {
        try {
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"error\":\"结果序列化失败\"}";
        }
    }

    /** 歌词阶段的三态结果：拿到文本 / 待续查 / 失败。 */
    private record LyricsResult(String text, String pendingJson, String errorJson) {
        static LyricsResult of(String text) { return new LyricsResult(text, null, null); }
        static LyricsResult pending(String json) { return new LyricsResult(null, json, null); }
        static LyricsResult failed(String json) { return new LyricsResult(null, null, json); }

        boolean isPending() { return pendingJson != null; }
        boolean isFailed() { return errorJson != null; }
    }

    /** 把「网络/状态码/非 JSON」统一成一句可回给模型的话。 */
    private static final class SongApiException extends RuntimeException {
        SongApiException(String message) {
            super(message);
        }
    }
}
