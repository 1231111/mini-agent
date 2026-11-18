package com.miniagent.web;

import com.miniagent.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import com.miniagent.application.TenantQuotaExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerResponseStatusTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void preservesUnauthorizedStatusAndReason() {
        ResponseStatusException exception = new ResponseStatusException(
                HttpStatus.UNAUTHORIZED, "memory service authentication required");

        ResponseEntity<ApiResponse<Void>> response = handler.handleResponseStatus(exception);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("memory service authentication required", response.getBody().getMessage());
    }

    @Test
    void preservesBadRequestStatus() {
        ResponseStatusException exception = new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "unsupported evolution operation");

        ResponseEntity<ApiResponse<Void>> response = handler.handleResponseStatus(exception);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void tenantQuotaIsAnHttp429WithRetryAfter() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleTenantQuotaExceeded(
                new TenantQuotaExceededException());

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("86400", response.getHeaders().getFirst("Retry-After"));
        assertEquals("AGENT.07.03", response.getBody().getCode());
    }

    @Test
    void redisOutageIsAServiceUnavailableWithRetryAfter() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleDataAccessUnavailable(
                new RedisConnectionFailureException("redis host is unreachable"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
        assertNotNull(response.getBody());
        assertEquals("服务暂不可用，请稍后重试", response.getBody().getMessage());
    }

    @Test
    void permanentDataIntegrityFailureIsNotReportedAsRetryableUnavailable() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleDataAccessUnavailable(
                new DataIntegrityViolationException("duplicate key"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(null, response.getHeaders().getFirst("Retry-After"));
        assertEquals("系统内部错误，请稍后重试", response.getBody().getMessage());
    }
}
