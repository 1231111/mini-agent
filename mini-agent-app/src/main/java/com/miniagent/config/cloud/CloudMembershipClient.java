package com.miniagent.config.cloud;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账号服务会员端点的 HTTP 客户端。
 *
 * <p>它与 {@link CloudAccountClient} 打同一个 base-url（账号服务），区别只有两点：
 * <ul>
 *   <li>会员端点要求 {@code X-Account-Internal-Key} —— 账号服务不签 token，
 *       没有"当前登录用户"的概念，所以会员查询的鉴权由<b>本侧</b>完成：</li>
 *   <li>调用方必须先把用户自己的 token 验掉，从中取出 userId 再传过去。
 *       {@code userId} 是调用方<b>担保</b>的，这是整个设计里最需要守住的边界，
 *       详见 app 侧 {@code MembershipController} 的说明。</li>
 * </ul>
 *
 * <h3>为什么不复用 RestClient 的自动反序列化</h3>
 *
 * <p>与 {@link CloudAccountClient} 同因：账号服务的业务失败是 <b>HTTP 200 + success:false</b>，
 * 直接 {@code body(SomeType.class)} 会把失败响应当成数据来解析，
 * 结果是"套餐不存在"变成一个 null 字段而不是一条错误。
 * 所以这里收 {@code byte[]} 自己解包。
 */
@Component
public class CloudMembershipClient {

    private static final Logger log = LoggerFactory.getLogger(CloudMembershipClient.class);

    private static final String HEADER_INTERNAL_KEY = "X-Account-Internal-Key";

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String internalKey;
    private final RestClient client;

    public CloudMembershipClient(
            ObjectMapper objectMapper,
            @Value("${agent.auth.cloud.base-url:}") String baseUrl,
            @Value("${agent.auth.cloud.internal-key:}") String internalKey,
            @Value("${agent.auth.cloud.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${agent.auth.cloud.read-timeout-ms:10000}") int readTimeoutMs) {
        this.objectMapper = objectMapper;
        this.baseUrl = normalize(baseUrl);
        this.internalKey = internalKey == null ? "" : internalKey.trim();

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.client = RestClient.builder().requestFactory(factory).build();

        // 判断用归一化后的字段，不用构造函数参数：参数可能是 "  " 这种只有空白的值，
        // 那时 isEmpty() 为 false、normalize() 之后才是空 —— 会走进"已配置"分支，
        // 于是日志说已配好、实际运行时一调用就报未配置。两处结论必须来自同一个值。
        if (this.baseUrl.isEmpty()) {
            log.info("会员服务: 未配置（agent.auth.cloud.base-url 为空）—— 会员端点不可用");
        } else if (this.internalKey.isEmpty()) {
            // 这是最容易漏的一步：base-url 配了、密钥没配，登录注册全正常，
            // 只有会员页 503。在这里先说清楚，省掉一次"为什么只有会员坏"的排查。
            log.warn("会员服务: 已配 base-url 但未配 agent.auth.cloud.internal-key —— "
                    + "会员端点会返回 MEMBER.02.02。登录与注册不受影响。");
        } else {
            log.info("会员服务: {}（服务间密钥已配置）", this.baseUrl);
        }
    }

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

    /** 会员端点是否可用：需要账号服务地址 + 服务间密钥两者齐备。 */
    public boolean available() {
        return !baseUrl.isEmpty() && !internalKey.isEmpty();
    }

    // ==================== 对外的三个能力 ====================

    public List<PlanView> plans() {
        return call("GET", "/api/membership/plans", null, new TypeReference<Envelope<List<PlanView>>>() {
        });
    }

    public MembershipView current(Long userId) {
        return call("GET", "/api/membership/current?userId=" + userId, null,
                new TypeReference<Envelope<MembershipView>>() {
                });
    }

    public OrderView createOrder(Long userId, String planCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("planCode", planCode);
        return call("POST", "/api/membership/orders", body, new TypeReference<Envelope<OrderView>>() {
        });
    }

    // ==================== 传输与解包 ====================

    private <T> T call(String method, String path, Object body, TypeReference<Envelope<T>> type) {
        if (!available()) {
            // 区分两种"不可用"：没配地址是部署没做，配了地址没配密钥是配漏了一项。
            // 合成一句话会让运维去改错地方。
            throw new CloudAccountException(
                    baseUrl.isEmpty() ? ErrorCode.AUTH_CLOUD_NOT_CONFIGURED : ErrorCode.MEMBER_INTERNAL_KEY_MISSING,
                    baseUrl.isEmpty()
                            ? "未配置云端账号服务（agent.auth.cloud.base-url）"
                            : "未配置服务间密钥（agent.auth.cloud.internal-key），会员接口未启用");
        }

        byte[] raw;
        try {
            RestClient.RequestBodySpec spec = client.method(org.springframework.http.HttpMethod.valueOf(method))
                    .uri(baseUrl + path)
                    .header(HEADER_INTERNAL_KEY, internalKey);
            raw = ("POST".equals(method)
                    ? spec.contentType(MediaType.APPLICATION_JSON).body(body)
                    : spec)
                    .retrieve()
                    .body(byte[].class);
        } catch (ResourceAccessException e) {
            // 与 CloudAccountClient 同理：地址只进日志。这条 message 会经 ApiResponse 返回给
            // 登录态的普通用户，而账号服务的部署位置不是他们需要知道的信息。
            log.warn("会员服务不可达: baseUrl={} path={} cause={}", baseUrl, path, rootMessage(e));
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_UNREACHABLE,
                    "无法连接账号服务：" + rootMessage(e), e);
        } catch (RestClientResponseException e) {
            // 403 多半是密钥不一致；503 是账号服务那边没配 internal-key。
            // 两者在日志里必须能区分，否则排查会从"改本机配置"开始，方向就错了。
            log.warn("会员服务返回 HTTP {}: path={}", e.getStatusCode().value(), path);
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_REJECTED,
                    "账号服务返回 HTTP " + e.getStatusCode().value(), e);
        }

        String text = raw == null ? "" : new String(raw, StandardCharsets.UTF_8);
        Envelope<T> envelope;
        try {
            envelope = objectMapper.readValue(text, type);
        } catch (Exception e) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE,
                    "账号服务返回的不是合法 JSON", e);
        }
        if (envelope == null) {
            throw new CloudAccountException(ErrorCode.AUTH_CLOUD_INVALID_RESPONSE,
                    "账号服务返回了空响应");
        }
        if (!envelope.success()) {
            // 错误码原样透传（MEMBER.01.01 套餐不存在 等），映射不到才退回兜底码。
            ErrorCode mapped = ErrorCode.ofCode(envelope.code());
            throw new CloudAccountException(mapped != null ? mapped : ErrorCode.AUTH_CLOUD_REJECTED,
                    envelope.message());
        }
        return envelope.data();
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }

    /**
     * {@code ApiResponse} 的解包容器。
     *
     * <p>{@code ignoreUnknown} 不是可选项：账号服务以后往 {@code data} 里加字段
     * （比如"会员到期前 7 天提醒"），这里不该因为不认识就整条失败。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Envelope<T>(boolean success, String code, String message, T data) {
    }

    // ==================== 与账号服务对应的视图 ====================
    // 字段名必须与账号服务侧逐个对齐 —— 这些名字就是线上契约。

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanView(String code, String name, long dailyTokenLimit, int maxConcurrentTasks,
                           long priceCents, String currency, int durationDays) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MembershipView(String planCode, String planName, long dailyTokenLimit,
                                 int maxConcurrentTasks, LocalDateTime expireAt,
                                 String sourceOrderNo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderView(Long orderId, String orderNo, Long userId, String planCode,
                            long amountCents, String currency, String status,
                            String channel, String channelTxnId, LocalDateTime paidAt) {
    }
}
