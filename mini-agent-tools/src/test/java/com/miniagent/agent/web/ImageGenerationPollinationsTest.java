package com.miniagent.agent.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pollinations 免 Key 后端的边界行为。
 *
 * <p>为什么值得单独测：它是唯一「没有凭据也能跑」的后端。一旦把失败误判成成功，
 * 整条降级链就断了 —— 用户看到裂图，模型却以为工具成功。所以这里用本地假服务器
 * 分别模拟「正常出图 / 限流 / 回 HTML 错误页 / 空响应」，
 * 并锁住「无 Key 时主后端选它、有 Key 时它只排最后」这条路由规则。</p>
 */
class ImageGenerationPollinationsTest {

    /** 1x1 JPEG：足够让魔数判定认出来，又不用额外维护测试资源文件。 */
    private static final byte[] TINY_JPEG = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a"
                    + "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAA"
                    + "AAAAAAAAAAAAAAAAn/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/9oACAEBAAA/AKp//9k=");

    private static final String DATA_PREFIX = "data:image/jpeg;base64,";

    private HttpServer server;
    private ImageGenerationService service;

    private volatile int status = 200;
    private volatile String contentType = "image/jpeg";
    private volatile byte[] body = TINY_JPEG;
    private volatile String lastPath = "";
    private volatile String retryAfterHeader = null;

    @BeforeEach
    void startFakePollinations() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prompt/", exchange -> {
            lastPath = exchange.getRequestURI().toString();
            byte[] payload = body;
            exchange.getResponseHeaders().add("content-type", contentType);
            if (retryAfterHeader != null) {
                exchange.getResponseHeaders().add("Retry-After", retryAfterHeader);
            }
            if (payload.length == 0) {
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, payload.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(payload);
                }
            }
            exchange.close();
        });
        server.start();

        service = new ImageGenerationService();
        setField("pollinationsBaseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        setField("pollinationsModel", "flux");
        setField("pollinationsEnabled", Boolean.TRUE);
    }

    @AfterEach
    void stopFakePollinations() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 正常出图返回可直接落盘的data_uri() throws Throwable {
        String result = callGenerate("a tiny red dot");

        assertTrue(result.startsWith(DATA_PREFIX), result);
        byte[] decoded = Base64.getDecoder().decode(result.substring(DATA_PREFIX.length()));
        assertEquals(0xFF, decoded[0] & 0xFF, "必须是 JPEG 魔数，否则落盘时会因扩展名判定失败退原链接");
        assertEquals(0xD8, decoded[1] & 0xFF);
        assertTrue(lastPath.contains("width=1024") && lastPath.contains("height=1024"),
                "square_hd 要映射成 1024x1024，实际: " + lastPath);
        assertTrue(lastPath.contains("nologo=true"), "不要水印，实际: " + lastPath);
    }

    /** 429 必须抛出，让 tryBackend 记熔断并降级；返回「成功」会让整条链失效。 */
    @Test
    void 限流时抛异常而不是当成功() {
        status = 429;
        contentType = "application/json";
        body = "{\"error\":\"rate limited\"}".getBytes(StandardCharsets.UTF_8);

        Throwable t = assertThrows(Throwable.class, () -> callGenerate("x"));
        assertTrue(t.getMessage().contains("HTTP 429"), t.getMessage());
    }

    /**
     * 真实 402 响应：Pollinations 每人每 IP 45 秒 1 张，超了回 402 FREE_LIMIT。
     * 必须解析出「还要等几秒」，否则用户只看到一句 HTTP 402 无从判断。
     */
    @Test
    void 免费额度用尽时抛出带等待秒数的限速异常() {
        status = 402;
        contentType = "application/json; charset=utf-8";
        retryAfterHeader = "33";
        body = ("{\"status\":402,\"success\":false,\"error\":{\"code\":\"FREE_LIMIT\","
                + "\"message\":\"Free limit reached: 1 image every 45 seconds per IP. Retry after 33s\"}}")
                .getBytes(StandardCharsets.UTF_8);

        Throwable t = assertThrows(Throwable.class, () -> callGenerate("x"));

        assertTrue(t instanceof ImageGenerationService.BackendRateLimitedException,
                "402 必须归为限速而不是普通故障，实际: " + t.getClass().getName());
        assertEquals(33L, ((ImageGenerationService.BackendRateLimitedException) t).retryAfterSeconds());
        assertTrue(t.getMessage().contains("HTTP 402"), t.getMessage());
        assertTrue(t.getMessage().contains("33 秒"), t.getMessage());
        assertTrue(t.getMessage().contains("限速"), t.getMessage());
    }

    /** 响应头缺失时正文里也有「Retry after 17s」，不能退化成「请稍后重试」。 */
    @Test
    void 响应头缺失时从正文解析等待秒数() {
        status = 402;
        contentType = "application/json";
        retryAfterHeader = null;
        body = ("{\"error\":{\"code\":\"FREE_LIMIT\","
                + "\"message\":\"Free limit reached. Retry after 17s\"}}")
                .getBytes(StandardCharsets.UTF_8);

        Throwable t = assertThrows(Throwable.class, () -> callGenerate("x"));

        assertEquals(17L, ((ImageGenerationService.BackendRateLimitedException) t).retryAfterSeconds(),
                "正文里的秒数要能被兜底解析，实际消息: " + t.getMessage());
    }

    /**
     * 限速不能计入熔断。
     *
     * <p>真实故障时「失败 3 次跳过后端 5 分钟」是对的；但限速的恢复窗口只有 45 秒，
     * 按故障记就会让一个 45 秒后就能用的后端被跳过 5 分钟，
     * 用户看到的是「刚才还能用，突然一直说熔断」。</p>
     */
    @Test
    void 限速不计入熔断() throws Exception {
        status = 402;
        contentType = "application/json";
        body = "{\"error\":{\"code\":\"FREE_LIMIT\"}}".getBytes(StandardCharsets.UTF_8);
        long deadline = System.currentTimeMillis() + 120_000L;

        for (int i = 0; i < 3; i++) {
            invoke("tryBackend",
                    new Class<?>[]{String.class, String.class, String.class, long.class},
                    "pollinations", "x", "square_hd", deadline);
        }

        Map<?, ?> states = (Map<?, ?>) readField("circuitStates");
        assertNull(states.get("pollinations"),
                "限速不该写入熔断计数，否则 45 秒后可用的后端会被跳过 5 分钟");
    }

    /** 对照：真正的故障仍然要记熔断，别把上面那条规则扩大成「永不熔断」。 */
    @Test
    void 普通故障仍然计入熔断() throws Exception {
        status = 500;
        contentType = "text/plain";
        body = "boom".getBytes(StandardCharsets.UTF_8);
        long deadline = System.currentTimeMillis() + 120_000L;

        invoke("tryBackend",
                new Class<?>[]{String.class, String.class, String.class, long.class},
                "pollinations", "x", "square_hd", deadline);

        Map<?, ?> states = (Map<?, ?>) readField("circuitStates");
        assertTrue(states.containsKey("pollinations"), "5xx 属于真故障，必须记熔断");
    }

    /** 网关回 HTML 错误页时 content-type 不是图片，必须拒绝，不能把 HTML 当 jpg 落盘。 */
    @Test
    void 回HTML错误页时抛异常() {
        contentType = "text/html";
        body = "<html><body>gateway error</body></html>".getBytes(StandardCharsets.UTF_8);

        Throwable t = assertThrows(Throwable.class, () -> callGenerate("x"));
        assertTrue(t.getMessage().contains("非图片"), t.getMessage());
    }

    @Test
    void 空响应时抛异常() {
        body = new byte[0];

        Throwable t = assertThrows(Throwable.class, () -> callGenerate("x"));
        assertTrue(t.getMessage().contains("空响应"), t.getMessage());
    }

    /** 公共 GET 接口把 prompt 放 URL 路径里，超长会被网关拒；截断也不能整条丢弃。 */
    @Test
    void 超长prompt被截断而不是整条丢弃() throws Throwable {
        String result = callGenerate("a".repeat(3000));

        assertTrue(result.startsWith(DATA_PREFIX), "截断后仍应正常出图");
        assertTrue(lastPath.length() < 1200, "URL 路径应被截断，实际长度=" + lastPath.length());
    }

    @Test
    void 没有任何Key时主后端选免Key兜底() throws Exception {
        clearAllKeys();
        assertEquals("pollinations", invoke("resolveBackend", new Class<?>[0]));
    }

    @Test
    void 有关键时免Key后端只排最后() throws Exception {
        clearAllKeys();
        setField("siliconflowKey", "sk-test");

        List<?> chain = (List<?>) invoke("buildFallbackChain", new Class<?>[]{String.class}, "siliconflow");
        assertEquals("siliconflow", chain.get(0), "有 Key 的后端优先");
        assertEquals("pollinations", chain.get(chain.size() - 1), "免 Key 后端只在全都没成时兜底");
    }

    @Test
    void 关闭开关后无Key回落到mimo() throws Exception {
        clearAllKeys();
        setField("pollinationsEnabled", Boolean.FALSE);

        assertEquals("mimo", invoke("resolveBackend", new Class<?>[0]));
    }

    // ========== 反射工具（本模块测试无需引入 spring-test） ==========

    private void clearAllKeys() throws Exception {
        setField("chatAnywhereKey", null);
        setField("mimoKey", null);
        setField("falKey", null);
        setField("siliconflowKey", null);
        setField("zhipuKey", null);
        setField("configuredBackend", "");
        setField("pollinationsEnabled", Boolean.TRUE);
    }

    private void setField(String name, Object value) throws Exception {
        Field f = ImageGenerationService.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private Object readField(String name) throws Exception {
        Field f = ImageGenerationService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(service);
    }

    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = ImageGenerationService.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        try {
            return m.invoke(service, args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getTargetException();
        }
    }

    private String callGenerate(String prompt) throws Throwable {
        Method m = ImageGenerationService.class.getDeclaredMethod(
                "generateViaPollinations", String.class, String.class);
        m.setAccessible(true);
        try {
            return (String) m.invoke(service, prompt, "square_hd");
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }
}
