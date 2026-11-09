package com.miniagent.config.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 云端账号服务的 HTTP 客户端。
 *
 * <p>由 {@code agent.auth.cloud.base-url} 配置。空值表示"没配云端"，此时
 * {@link #configured()} 为 false，注册/登录仍走本地账号 —— 这样开发态和单机测试
 * 不需要为了跑一次登录去起第二份实例。
 *
 * <p>打的是账号服务（{@code mini-agent-account}）的 {@code POST /api/tokens} 与
 * {@code POST /api/users}。这两条路径与云端 agent 自己的同名端点<b>刻意一致</b>：
 * 切换"账号逻辑在本地"与"账号逻辑在账号服务"时只换 base-url，调用方一行都不用改。
 *
 * <p>账号服务返回 Account 身份和专供 memory-service 使用的 memory access token；
 * 本地会话仍由桌面端本地后端独立管理。本类负责解析账号身份与云端记忆凭证。
 *
 * <h3>两个必须做对的地方</h3>
 *
 * <p><b>1. 业务失败是 HTTP 200。</b>{@code ApiResponse} 的约定是业务错误也走 200，
 * 靠 {@code success:false} 表达。所以判断成败只能看 {@code success}，
 * 看 HTTP 状态码会把"用户名已被占用"当成注册成功。
 *
 * <p><b>2. 业务错误码要原样透传。</b>云端返回的 {@code code} 就是它自己的
 * {@link ErrorCode}（如密码错 {@code AUTH.01.01}、重名 {@code AUTH.01.02}）。
 * 如果一律包成 {@link ErrorCode#AUTH_CLOUD_REJECTED}，前端就无法区分
 * "密码输错了"和"云端拒绝了这个请求"，用户会被引导去查网络，而实际该改的是密码。
 * 只有云端返回了本地不认识的码，才退回 AUTH_CLOUD_REJECTED。
 *
 * <h3>为什么"连不上"要重试，而"口令错"不能重试</h3>
 *
 * <p>公网到云端的链路是<b>间歇性</b>劣化的：同一个地址，上一秒 40 ms 返回，
 * 下一秒 SYN 丢包、几十秒后才恢复。这种情况下 3 秒的硬超时会直接把一次本来
 * 能成功的登录判死，用户看到的是 {@code AUTH.03.01}，实际账号密码完全没问题。
 *
 * <p>但重试必须分阶段判，不能一律重试。{@code ResourceAccessException} 的根因有两种：
 * <ul>
 *   <li><b>连接阶段失败</b>（{@link ConnectException} / {@code Connect timed out}）——
 *       请求<b>从未到达</b>服务端，任何方法都能安全重发。</li>
 *   <li><b>已建连但没等到响应</b>（{@code Read timed out}）—— 请求<b>已经发出去了</b>。
 *       对注册、票据兑换这类非幂等操作，服务端可能已经落库，重发会把用户推进
 *       "用户名已被占用"这条错路。所以这种情况下只允许幂等操作重试。</li>
 * </ul>
 *
 * <p>总耗时有上限（{@code total-budget-ms}）：登录在启动路径上是阻塞的，
 * 不能因为云端整体不可用就让界面无限转圈。
 */
@Component
public class CloudAccountClient {

    private static final Logger log = LoggerFactory.getLogger(CloudAccountClient.class);

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final RestClient client;
    /** 总尝试次数（含首次）。1 表示不重试。 */
    private final int maxAttempts;
    /** 第 n 次失败后的等待 = retryBackoffMs × n，线性退避。 */
    private final long retryBackoffMs;
    /** 整次调用的时间上限：退避 + 重试都算在里面，避免登录无限等待。 */
    private final long totalBudgetMs;

    public CloudAccountClient(
            ObjectMapper objectMapper,
            @Value("${agent.auth.cloud.base-url:}") String baseUrl,
            @Value("${agent.auth.cloud.connect-timeout-ms:4000}") int connectTimeoutMs,
            @Value("${agent.auth.cloud.read-timeout-ms:8000}") int readTimeoutMs,
            @Value("${agent.auth.cloud.max-attempts:3}") int maxAttempts,
            @Value("${agent.auth.cloud.retry-backoff-ms:400}") long retryBackoffMs,
            @Value("${agent.auth.cloud.total-budget-ms:20000}") long totalBudgetMs) {
        this.objectMapper = objectMapper;
        this.baseUrl = normalize(baseUrl);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
        this.totalBudgetMs = Math.max(1, totalBudgetMs);

        // 超时必须显式设。默认是"无限等待"，而登录是启动路径上的阻塞步骤 ——
        // 云端宕了却只丢包不拒绝连接时，界面会无限转圈，正是最难定位的那种故障。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.client = RestClient.builder().requestFactory(factory).build();

        if (configured()) {
            log.info("云端账号服务: {}（连接超时 {} ms，读取超时 {} ms，最多尝试 {} 次，总预算 {} ms）",
                    this.baseUrl, connectTimeoutMs, readTimeoutMs, this.maxAttempts, this.totalBudgetMs);
        } else {
            log.info("云端账号服务: 未配置（agent.auth.cloud.base-url 为空）—— 注册/登录走本地账号");
        }
    }

    /** 去掉尾部斜杠，避免拼出 {@code http://host//api/tokens}。 */
    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    public boolean configured() {
        return !baseUrl.isEmpty();
    }

    public CloudUser login(String username, String password) {
        // 登录幂等：同一组账号密码重复提交不产生任何副作用，读超时也可以重发。
        return call("/api/tokens", credentials(username, password, null), true);
    }

    public CloudUser register(String username, String password, String displayName) {
        // 注册非幂等：请求已到达但没有回音时，账号可能已经建好了。
        // 再发一次会让用户看到"用户名已被占用"，而其实那次注册是成功的。
        return call("/api/users", credentials(username, password, displayName), false);
    }

    /** 用网页注册换来的一次性凭证换身份。凭证本身就是秘密，不再带密码。 */
    public CloudUser redeem(String ticket) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket", ticket);
        // 票据一次性。同上：只在"请求肯定没发出去"的阶段允许重试。
        return call("/api/desktop-tickets/redeem", body, false);
    }

    public CloudUser refreshMemoryCredential(String refreshCredential) {
        if (refreshCredential == null || refreshCredential.isBlank()) {
            throw new CloudAccountException(ErrorCode.AUTH_NOT_AUTHENTICATED, "缺少记忆服务续期凭证");
        }
        // 续期幂等：多签一张 token 不改变任何状态，旧 token 到期自然失效。
        return call("/internal/oauth/refresh", Map.of("refreshCredential", refreshCredential), true);
    }

    /**
     * 检查云端是否可达且健康；不可达时抛 {@link CloudAccountException}。
     *
     * <p>打的是 {@code /actuator/health}：它在本服务的 PUBLIC_PATHS 里，
     * 是唯一一个既无需凭据、又真的代表"后端可用"的端点。
     * 刻意不把"端口有人应答"当信号 —— 局域网里同端口的另一个进程也会应答，
     * 那样会把"连到了别人的服务"判成健康。
     */
    public void health() {
        if (!configured()) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_NOT_CONFIGURED, null);
        }

        // GET，幂等，可以放心重试。
        byte[] raw = withRetry("/actuator/health", true, () -> {
            try {
                return client.get()
                        .uri(baseUrl + "/actuator/health")
                        .retrieve()
                        .body(byte[].class);
            } catch (RestClientResponseException e) {
                throw new CloudAccountException(ErrorCode.AUTH_CLOUD_REJECTED,
                        "云端账号服务健康检查返回 HTTP " + e.getStatusCode().value(), e);
            }
        });

        String text = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
        String status;
        try {
            status = objectMapper.readTree(text).path("status").asText("");
        } catch (Exception e) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE,
                    "云端账号服务健康检查返回的不是合法 JSON", e);
        }
        if (!"UP".equals(status)) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_REJECTED,
                    "云端账号服务健康检查未通过（status=" + (status.isEmpty() ? "缺失" : status) + "）");
        }
    }

    private static Map<String, Object> credentials(String username, String password, String displayName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        if (displayName != null) {
            body.put("displayName", displayName);
        }
        return body;
    }

    private CloudUser call(String path, Map<String, Object> payload, boolean idempotent) {
        if (!configured()) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_NOT_CONFIGURED, null);
        }
        byte[] raw = withRetry(path, idempotent, () -> {
            try {
                // 用 byte[] 而不是 String 接收：ByteArrayHttpMessageConverter 匹配 */*，
                // 不会因为对方把 Content-Type 写成 application/json 而挑不到转换器；
                // 编码也由这里显式定成 UTF-8，不依赖对方声明的 charset。
                return client.post()
                        .uri(baseUrl + path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload)
                        .retrieve()
                        .body(byte[].class);
            } catch (RestClientResponseException e) {
                // 对方明确回了 4xx/5xx：这是"业务拒绝"，不是链路问题，原样上报、绝不重试。
                throw new CloudAccountException(ErrorCode.AUTH_CLOUD_REJECTED,
                        "云端账号服务返回 HTTP " + e.getStatusCode().value(), e);
            }
        });
        return parse(raw == null ? "" : new String(raw, StandardCharsets.UTF_8));
    }

    /**
     * 带阶段判定与总预算的有限重试。
     *
     * <p>为什么捕的是 {@link RestClientException} 而不是更"贴切"的
     * {@link ResourceAccessException}：Spring 只在 <b>连接阶段</b> 失败时抛后者；
     * 一旦 TCP 建好了、卡在读响应头，异常是在
     * {@code DefaultRestClient.readWithMessageConverters} 里被包成<b>普通</b>
     * {@code RestClientException}（"Error while extracting response"）的。
     * 只捕前者会漏掉最常见的那种超时 —— 实测就是这么漏的。
     *
     * <p>所以这里按<b>根因</b>判断：根因是 {@link java.io.IOException}（
     * 含 {@code Connect timed out} / {@code Read timed out} / 连接被拒 / 结尾截断）
     * 才算链路问题；否则是"响应读不成目标类型"这类非链路故障，直接上报。
     * 业务拒绝（4xx/5xx）在调用方的 lambda 里就已经转成 {@link CloudAccountException}，
     * 根本到不了这里，因此不会被重试。
     */
    private <T> T withRetry(String path, boolean idempotent, Supplier<T> action) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(totalBudgetMs);
        for (int attempt = 1; ; attempt++) {
            try {
                return action.get();
            } catch (RestClientException e) {
                if (!causedByIo(e)) {
                    throw new CloudAccountException(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE,
                            "云端账号服务响应无法解析：" + rootMessage(e), e);
                }
                boolean beforeSent = failedBeforeRequestSent(e);
                boolean canRetry = attempt < maxAttempts
                        && System.nanoTime() < deadline
                        && (beforeSent || idempotent);
                if (!canRetry) {
                    // 地址只进日志、不进 message。这条 message 会经 AUTH.03.01 原样返回给
                    // POST /api/tokens 的调用方，而该路径在 SecurityConfig.PUBLIC_PATHS 里 ——
                    // 把云端部署位置写进响应体，等于给任何能访问本机端口的人一个探测入口。
                    // 排查需要地址时看服务端日志即可。
                    log.warn("云端账号服务不可达（共尝试 {} 次）: baseUrl={} path={} cause={}",
                            attempt, baseUrl, path, rootMessage(e));
                    throw new CloudAccountException(ErrorCode.AUTH_CLOUD_UNREACHABLE,
                            unreachableMessage(e, attempt), e);
                }
                long backoff = retryBackoffMs * attempt;
                log.warn("云端账号服务不可达（第 {}/{} 次，{}），{} ms 后重试: baseUrl={} path={} cause={}",
                        attempt, maxAttempts, beforeSent ? "请求未发出" : "请求已发出",
                        backoff, baseUrl, path, rootMessage(e));
                sleep(backoff);
            }
        }
    }

    /** 根因链里有没有 I/O 失败。有才是"到不了/没等到"，没有多半是响应体格式问题。 */
    private static boolean causedByIo(Throwable e) {
        Throwable cur = e;
        for (int guard = 0; cur != null && guard < 16; guard++) {
            if (cur instanceof IOException) {
                return true;
            }
            cur = (cur.getCause() == cur) ? null : cur.getCause();
        }
        return false;
    }

    /**
     * 判断失败发生在"请求发出之前"还是"之后"。
     *
     * <p>这个判断决定了非幂等操作能不能重试：连接阶段失败说明请求根本没到服务端，
     * 重发是安全的；而读超时说明请求已经发出去了，服务端可能已经执行。
     *
     * <p>{@link java.net.HttpURLConnection} 用异常 message 区分这两段
     * （{@code "Connect timed out"} / {@code "Read timed out"}），没有更细的类型可用，
     * 所以只能按 message 判。判不出来时一律返回 {@code false}（按"已发出"处理）——
     * 宁可少重试一次，也不能让一次已经成功的注册被重发成"用户名已被占用"。
     *
     * <p>包级可见是为了能被测试直接覆盖：这是整条重试链路上唯一靠字符串判定的地方，
     * 一旦 JDK 改了措辞就会静默退化成"永不重试"，必须有测试钉住。
     */
    static boolean failedBeforeRequestSent(Throwable e) {
        Throwable cur = e;
        for (int guard = 0; cur != null && guard < 16; guard++) {
            if (cur instanceof ConnectException || cur instanceof NoRouteToHostException) {
                // 连接被拒 / 无路由：TCP 都没建起来，请求肯定没到。
                return true;
            }
            if (cur instanceof UnknownHostException) {
                // DNS 解析失败：重试没有任何意义，但也确实一条请求都没发出去。
                return true;
            }
            if (cur instanceof SocketTimeoutException) {
                String msg = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase(Locale.ROOT);
                return msg.contains("connect timed out");
            }
            cur = (cur.getCause() == cur) ? null : cur.getCause();
        }
        return false;
    }

    private static String unreachableMessage(Throwable e, int attempts) {
        String base = "无法连接云端账号服务：" + rootMessage(e);
        return attempts > 1 ? base + "（已自动重试 " + (attempts - 1) + " 次仍失败）" : base;
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_UNREACHABLE, "等待云端重试时被中断", ie);
        }
    }

    private CloudUser parse(String text) {
        JsonNode root;
        try {
            root = objectMapper.readTree(text);
        } catch (Exception e) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE,
                    "云端账号服务返回的不是合法 JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE,
                    "云端账号服务返回的 JSON 顶层不是对象");
        }

        if (!root.path("success").asBoolean(false)) {
            String code = root.path("code").asText("");
            String message = root.path("message").asText("");
            ErrorCode mapped = ErrorCode.ofCode(code);
            throw new CloudAccountException(
                    mapped != null ? mapped : ErrorCode.AUTH_CLOUD_REJECTED, message);
        }

        JsonNode data = root.get("data");
        if (data == null || !data.isObject()) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE,
                    "云端账号服务返回 success=true 但缺少 data 对象");
        }

        // userId 在云端是 Long，这里统一当字符串取，再交给本地做映射键 ——
        // 不对它的类型与形状做假设（换成 UUID 也不用改代码）。
        String externalId = text(data, "userId");
        if (externalId == null) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE,
                    "云端账号服务响应缺少 data.userId");
        }
        String username = text(data, "username");
        if (username == null) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE,
                    "云端账号服务响应缺少 data.username");
        }

        String cloudTenantId = text(data, "tenantId");
        String memoryToken = text(data, "memoryToken");
        String memoryRefreshCredential = text(data, "memoryRefreshCredential");
        if (cloudTenantId == null || memoryToken == null || memoryRefreshCredential == null) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE,
                    "云端账号服务响应缺少云端租户或记忆访问凭证");
        }
        return new CloudUser(externalId, username, text(data, "displayName"), text(data, "role"),
                cloudTenantId, memoryToken, memoryRefreshCredential);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String s = value.asText();
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 取异常链最内层的原因，避免给用户一句无处下手的复合异常描述。 */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }
}
