package com.miniagent.agent.web;

import com.miniagent.common.context.OwnerContext;

import com.miniagent.config.storage.MediaStorage;
import com.miniagent.common.io.AgentDataPaths;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成图落盘：把后端的临时链接换成本机资产。
 *
 * <p>挡的回归：硅基流动返回 {@code s3.siliconflow.cn/temporary/…&X-Amz-Expires=3600}（1 小时），
 * 智谱返回带 {@code Expires} 的 ufileos 链接。以前这两个 URL 被原样拼进 markdown，
 * 于是「当场能看、过一会儿历史记录里裂图、断网打不开」。</p>
 *
 * <p>用本地 HttpServer 而不是真调 API：考的是「下载 → 判类型 → 写 media/generated → 返回站内 URL」
 * 这条链路本身，不该依赖外网与账号余额。</p>
 */
class ImagePersistTest {

    /** 1x1 透明 PNG 的完整字节（含魔数，够判类型）。 */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    @TempDir
    Path dataDir;

    private HttpServer server;
    private int port;
    private ImageGenerationService service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 故意用 application/octet-stream：对象存储常这么标，扩展名只能靠魔数判
        server.createContext("/a.png", exchange -> respond(exchange, 200, "application/octet-stream", PNG));
        server.createContext("/notimage.png", exchange -> respond(exchange, 200, "text/html; charset=utf-8",
                "<html>oops</html>".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/gone.png", exchange -> respond(exchange, 404, "text/plain",
                "not found".getBytes(StandardCharsets.UTF_8)));
        server.start();
        port = server.getAddress().getPort();

        MediaStorage mediaStorage = new MediaStorage();
        setField(mediaStorage, "paths", new AgentDataPaths(dataDir.toString()));
        service = new ImageGenerationService();
        setField(service, "mediaStorage", mediaStorage);
        OwnerContext.setCurrentUser(7L);
    }

    @AfterEach
    void tearDown() {
        OwnerContext.clearCurrentUser();
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
                                String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private Path ownerDir() {
        return dataDir.resolve("media").resolve("generated").resolve("7");
    }

    @Test
    void remoteImageIsDownloadedAndStoredUnderOwnerDir() throws Exception {
        String stored = service.persistImage(url("/a.png"), new OwnerContext(7L, null));

        assertTrue(stored.startsWith("/api/generated-media/7/img_"),
                "必须换成受保护的站内地址，而不是继续用会过期的对象存储链接: " + stored);
        assertTrue(stored.endsWith(".png"), "扩展名要按魔数落到 png: " + stored);

        String fileName = stored.substring(stored.lastIndexOf('/') + 1);
        Path saved = ownerDir().resolve(fileName);
        assertTrue(Files.isRegularFile(saved), "文件必须真的写进 media/generated/7/");
        assertArrayEquals(PNG, Files.readAllBytes(saved), "落盘字节要和下载到的完全一致");
        assertEquals(1, countFiles(ownerDir()), "一次生成只应产生一个文件");
    }

    /** 对端返回的不是图片（比如网关吐了一段 HTML 报错页）→ 不能存成图片，退回原链接。 */
    @Test
    void nonImageResponseFallsBackToOriginalUrl() throws Exception {
        String original = url("/notimage.png");

        assertEquals(original, service.persistImage(original, new OwnerContext(7L, null)));
        assertTrue(countFiles(ownerDir()) == 0, "判不出类型时不能落盘");
    }

    /** 下载失败（404）→ 退回原链接，工具不该因为这一段落盘而失败。 */
    @Test
    void downloadFailureFallsBackToOriginalUrl() {
        String original = url("/gone.png");

        assertEquals(original, service.persistImage(original, new OwnerContext(7L, null)));
    }

    /** 已经是站内地址就不重复下载。 */
    @Test
    void inAppUrlIsLeftUntouched() {
        String appUrl = "/api/generated-media/7/img_1_2.png";

        assertEquals(appUrl, service.persistImage(appUrl, new OwnerContext(7L, null)));
    }

    private static long countFiles(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        }
    }
}
