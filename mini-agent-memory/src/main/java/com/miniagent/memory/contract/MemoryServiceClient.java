package com.miniagent.memory.contract;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.ApiResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;

/**
 * 调 memory-service 的最小 HTTP 客户端：透传 account 签发的 opaque 令牌，统一拆 {@link ApiResponse}。
 * 超时按用途各建一个实例（读路径要短）。
 */
public class MemoryServiceClient {

    /** 远端返回失败（业务码或 HTTP 错误）或网络异常。code 为远端 ErrorCode，网络异常时为空。 */
    public static class CallFailed extends RuntimeException {
        private final String code;
        private final int httpStatus;

        public CallFailed(String code, int httpStatus, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.httpStatus = httpStatus;
        }

        public String code() {
            return code;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }

    private final RestClient http;
    private final ObjectMapper json;

    public MemoryServiceClient(String baseUrl, Duration timeout, ObjectMapper json) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.json = json;
    }

    /** 成功时返回 data（可为 null）；失败一律抛 {@link CallFailed}。 */
    public <T> T call(HttpMethod method, String uri, Object body, String accessToken,
                      Class<T> dataType) {
        return call(method, uri, body, accessToken,
                json.getTypeFactory().constructType(dataType));
    }

    public <T> T call(HttpMethod method, String uri, Object body, String accessToken,
                      JavaType dataType) {
        JavaType envelope = json.getTypeFactory()
                .constructParametricType(ApiResponse.class, dataType);
        if (accessToken == null || accessToken.isBlank()) {
            throw new CallFailed(null, 401, "Account-issued memory access token is required", null);
        }
        ApiResponse<T> response;
        int status;
        try {
            RestClient.RequestBodySpec spec = http.method(method).uri(uri)
                    .header(ServiceToken.HEADER, ServiceToken.BEARER + accessToken)
                    .accept(MediaType.APPLICATION_JSON);
            if (body != null) {
                spec.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            var entity = spec.retrieve().onStatus(s -> true, (req, res) -> {
            }).toEntity(byte[].class);
            status = entity.getStatusCode().value();
            byte[] raw = entity.getBody();
            response = raw == null || raw.length == 0 ? null : json.readValue(raw, envelope);
        } catch (IOException | RuntimeException e) {
            throw new CallFailed(null, 0, method + " " + uri + " 调用失败: " + e.getMessage(), e);
        }
        if (response == null) {
            throw new CallFailed(null, status, method + " " + uri + " 返回空响应 HTTP " + status,
                    null);
        }
        if (!response.isSuccess()) {
            throw new CallFailed(response.getCode(), status, response.getMessage(), null);
        }
        return response.getData();
    }
}
