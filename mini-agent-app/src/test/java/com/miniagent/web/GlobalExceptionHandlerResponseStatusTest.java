package com.miniagent.web;

import com.miniagent.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
}
