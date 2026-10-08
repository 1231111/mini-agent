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
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

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
 * <p>账号服务不签发 token（见其 {@code AccountApplication} 的说明），响应里也没有 token 字段。
 * 这不是缺项：token 由收到用户请求的那一侧自己签 —— 客户机本地后端签本地会话，
 * 云端 agent 签它自己的。好处是 HS256 密钥不必在两个服务之间同步。
 * 本类因此只回答"这个用户名+密码对应哪个身份"。
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
 */
@Component
public class CloudAccountClient {

    private static final Logger log = LoggerFactory.getLogger(CloudAccountClient.class);

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final RestClient client;

    public CloudAccountClient(
            ObjectMapper objectMapper,
            @Value("${agent.auth.cloud.base-url:}") String baseUrl,
            @Value("${agent.auth.cloud.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${agent.auth.cloud.read-timeout-ms:10000}") int readTimeoutMs) {
        this.objectMapper = objectMapper;
        this.baseUrl = normalize(baseUrl);

        // 超时必须显式设。默认是"无限等待"，而登录是启动路径上的阻塞步骤 ——
        // 云端宕了却只丢包不拒绝连接时，界面会无限转圈，正是最难定位的那种故障。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.client = RestClient.builder().requestFactory(factory).build();

        if (configured()) {
            log.info("云端账号服务: {}（连接超时 {} ms，读取超时 {} ms）", this.baseUrl, connectTimeoutMs, readTimeoutMs);
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
        return call("/api/tokens", credentials(username, password, null));
    }

    public CloudUser register(String username, String password, String displayName) {
        return call("/api/users", credentials(username, password, displayName));
    }

    /** 用网页注册换来的一次性凭证换身份。凭证本身就是秘密，不再带密码。 */
    public CloudUser redeem(String ticket) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ticket", ticket);
        return call("/api/desktop-tickets/redeem", body);
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

        byte[] raw;
        try {
            raw = client.get()
                    .uri(baseUrl + "/actuator/health")
                    .retrieve()
                    .body(byte[].class);
        } catch (ResourceAccessException e) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_UNREACHABLE,
                    "无法连接云端账号服务：" + rootMessage(e), e);
        } catch (RestClientResponseException e) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_REJECTED,
                    "云端账号服务健康检查返回 HTTP " + e.getStatusCode().value(), e);
        }

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

    private CloudUser call(String path, Map<String, Object> payload) {
        if (!configured()) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_NOT_CONFIGURED, null);
        }

        byte[] raw;
        try {
            // 用 byte[] 而不是 String 接收：ByteArrayHttpMessageConverter 匹配 */*，
            // 不会因为对方把 Content-Type 写成 application/json 而挑不到转换器；
            // 编码也由这里显式定成 UTF-8，不依赖对方声明的 charset。
            raw = client.post()
                    .uri(baseUrl + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(byte[].class);
        } catch (ResourceAccessException e) {
            // 连接被拒 / DNS 解析失败 / 超时 —— 都属于"根本到不了"。
            // 远端主动返回 4xx/5xx 不走这里，走下面的 RestClientResponseException。
            //
            // 地址只进日志、不进 message。这条 message 会经 AUTH.03.01 原样返回给
            // POST /api/tokens 的调用方，而该路径在 SecurityConfig.PUBLIC_PATHS 里 ——
            // 把云端部署位置写进响应体，等于给任何能访问本机端口的人一个探测入口。
            // 排查需要地址时看服务端日志即可。
            log.warn("云端账号服务不可达: baseUrl={} path={} cause={}", baseUrl, path, rootMessage(e));
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_UNREACHABLE,
                    "无法连接云端账号服务：" + rootMessage(e), e);
        } catch (RestClientResponseException e) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_REJECTED,
                    "云端账号服务返回 HTTP " + e.getStatusCode().value(), e);
        }

        return parse(raw == null ? "" : new String(raw, StandardCharsets.UTF_8));
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

        return new CloudUser(externalId, username, text(data, "displayName"), text(data, "role"));
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
