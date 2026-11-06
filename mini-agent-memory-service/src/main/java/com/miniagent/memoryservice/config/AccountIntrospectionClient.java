package com.miniagent.memoryservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.ApiResponse;
import com.miniagent.memory.contract.MemoryAccessToken;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

@Component
public class AccountIntrospectionClient {
    private final RestClient http;
    private final String internalKey;
    private final ObjectMapper json;

    public AccountIntrospectionClient(MemoryServiceProperties properties, ObjectMapper json) {
        this.json = json;
        this.internalKey = properties.accountInternalKey();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.accountConnectTimeoutMs());
        factory.setReadTimeout(properties.accountReadTimeoutMs());
        this.http = RestClient.builder().baseUrl(properties.accountBaseUrl())
                .requestFactory(factory).build();
    }

    public MemoryAccessToken.Claims introspect(String token) {
        try {
            var result = http.post().uri("/internal/oauth/introspect")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Account-Internal-Key", internalKey)
                    .body(new Request(token)).retrieve()
                    .onStatus(status -> true, (request, response) -> { })
                    .toEntity(byte[].class);
            byte[] raw = result.getBody();
            if (result.getStatusCode().value() == 401) {
                throw new InvalidTokenException("account rejected memory access token");
            }
            if (!result.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("account introspection returned HTTP "
                        + result.getStatusCode().value());
            }
            if (raw == null || raw.length == 0) {
                throw new IllegalStateException("account introspection returned an empty response");
            }
            ApiResponse<MemoryAccessToken.Claims> response = json.readValue(raw,
                    json.getTypeFactory().constructParametricType(ApiResponse.class,
                            MemoryAccessToken.Claims.class));
            if (response == null || !response.isSuccess() || response.getData() == null) {
                throw new InvalidTokenException("account rejected memory access token");
            }
            return response.getData();
        } catch (ResourceAccessException e) {
            throw new AccountUnavailableException("account service is unavailable", e);
        } catch (InvalidTokenException | AccountUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("account introspection failed", e);
        }
    }

    public record Request(String token) {
    }

    public static final class AccountUnavailableException extends RuntimeException {
        public AccountUnavailableException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String message) { super(message); }
    }
}
