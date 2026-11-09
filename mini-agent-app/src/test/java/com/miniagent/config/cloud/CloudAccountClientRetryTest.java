package com.miniagent.config.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.ErrorCode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CloudAccountClient} 的重试策略。
 *
 * <p>这几个用例钉的是同一条设计约束的两面：<b>连不上可以重试，业务拒绝不可以</b>，
 * 而"连不上"内部还要再分「请求没发出去」与「请求已发出、只是没等到回音」——
 * 后者对注册、票据兑换这类非幂等操作重试，会把一次已经成功的注册变成
 * "用户名已被占用"。
 */
class CloudAccountClientRetryTest {

    /** 连接被拒 = TCP 都没建起来 = 请求肯定没到服务端，非幂等的注册也应该重试。 */
    @Test
    void connectionRefusedIsRetriedEvenForRegister() throws Exception {
        int port = closedPort();
        CloudAccountClient client = new CloudAccountClient(
                new ObjectMapper(), "http://127.0.0.1:" + port, 500, 500, 3, 10, 5_000);

        CloudAccountException e = assertThrows(CloudAccountException.class,
                () -> client.register("someone", "password", "某人"));

        assertEquals(ErrorCode.AUTH_CLOUD_UNREACHABLE, e.errorCode());
        // 3 次尝试 = 首次 + 2 次重试，且必须让用户知道"不是账号的问题"
        assertTrue(e.getMessage().contains("已自动重试 2 次"),
                "应把重试次数写进给用户看的文案: " + e.getMessage());
    }

    /**
     * 读超时 = 请求已经发到服务端了。
     *
     * <p>同一个慢服务：注册只打一次（宁可失败也不重复建号），登录打满两次
     * （幂等，重发无副作用）。
     */
    @Test
    void readTimeoutRetriesLoginButNotRegister() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = slowServer(hits, 400, 200);
        try {
            CloudAccountClient client = new CloudAccountClient(
                    new ObjectMapper(), base(server), 500, 150, 2, 10, 10_000);

            assertThrows(CloudAccountException.class,
                    () -> client.register("someone", "password", "某人"));
            assertEquals(1, hits.get(), "注册在读超时后不得重发");

            hits.set(0);
            assertThrows(CloudAccountException.class,
                    () -> client.login("someone", "password"));
            assertEquals(2, hits.get(), "登录幂等，应重试到 max-attempts");
        } finally {
            server.stop(0);
        }
    }

    /** 4xx/5xx 是业务拒绝，不是链路问题：重试多少次结果都一样，只让用户多等。 */
    @Test
    void serverErrorIsNotRetried() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            respond(exchange, 503, "服务端错误");
        });
        server.setExecutor(daemonExecutor());
        server.start();
        try {
            CloudAccountClient client = new CloudAccountClient(
                    new ObjectMapper(), base(server), 500, 2_000, 3, 10, 10_000);

            CloudAccountException e = assertThrows(CloudAccountException.class,
                    () -> client.login("someone", "password"));

            assertEquals(ErrorCode.AUTH_CLOUD_REJECTED, e.errorCode());
            assertEquals(1, hits.get(), "HTTP 503 不得重试");
        } finally {
            server.stop(0);
        }
    }

    /**
     * 阶段判定本身。
     *
     * <p>这是整条重试链路上唯一靠异常 message 判定的地方（HttpURLConnection 没有
     * 更细的类型），一旦 JDK 改措辞就会静默退化成"永不重试"，所以单独钉住。
     */
    @Test
    void phaseClassifierDistinguishesConnectFromReadTimeout() {
        assertTrue(CloudAccountClient.failedBeforeRequestSent(new ConnectException("Connection refused")));
        assertTrue(CloudAccountClient.failedBeforeRequestSent(
                new RuntimeException(new ConnectException("Connection refused"))));
        assertTrue(CloudAccountClient.failedBeforeRequestSent(new SocketTimeoutException("Connect timed out")));
        assertTrue(CloudAccountClient.failedBeforeRequestSent(new UnknownHostException("no-such-host")));

        assertFalse(CloudAccountClient.failedBeforeRequestSent(new SocketTimeoutException("Read timed out")));
        assertFalse(CloudAccountClient.failedBeforeRequestSent(new RuntimeException(
                new IOException("Unexpected end of file from server"))));
        assertFalse(CloudAccountClient.failedBeforeRequestSent(null));
    }

    // ---------- 测试脚手架 ----------

    private static String base(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 先占一个端口再立刻释放，用于稳定地构造"连接被拒"。 */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** 每个请求都睡 {@code sleepMs} 毫秒再回应，用于稳定地触发读超时。 */
    private static HttpServer slowServer(AtomicInteger hits, long sleepMs, int status) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, status, "{}");
        });
        server.setExecutor(daemonExecutor());
        server.start();
        return server;
    }

    /** 守护线程池：HttpServer 默认用主线程池之外的执行器，非守护线程会拖住 surefire 退出。 */
    private static ExecutorService daemonExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "retry-test-http");
            t.setDaemon(true);
            return t;
        });
    }

    private static void respond(HttpExchange exchange, int status, String text) {
        try {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException ignored) {
            // 客户端已放弃这次请求（读超时用例里必然发生）
        } finally {
            exchange.close();
        }
    }
}
