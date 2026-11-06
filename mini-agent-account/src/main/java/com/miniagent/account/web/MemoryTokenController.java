package com.miniagent.account.web;

import com.miniagent.account.service.MemoryAccessTokenService;
import com.miniagent.account.web.dto.AccountUserResponse;
import com.miniagent.memory.contract.MemoryAccessToken;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MemoryTokenController {
    private final MemoryAccessTokenService tokens;

    public MemoryTokenController(MemoryAccessTokenService tokens) {
        this.tokens = tokens;
    }

    public record IntrospectRequest(String token) {
    }

    public record RefreshRequest(String refreshCredential) { }

    @PostMapping(value = "/internal/oauth/refresh", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<AccountUserResponse>> refresh(
            @RequestBody(required = false) RefreshRequest request) {
        if (request == null || request.refreshCredential() == null || request.refreshCredential().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED));
        }
        try {
            MemoryAccessTokenService.IssuedCredential issued = tokens.refresh(request.refreshCredential());
            return ResponseEntity.ok(ApiResponse.ok(AccountUserResponse.of(issued.user(),
                    issued.accessToken(), issued.refreshCredential())));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED));
        }
    }

    @PostMapping(value = "/internal/oauth/introspect", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<MemoryAccessToken.Claims>> introspect(
            @RequestBody(required = false) IntrospectRequest request,
            jakarta.servlet.http.HttpServletRequest servletRequest) {
        if (request == null || request.token() == null || request.token().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED));
        }
        String presented = servletRequest.getHeader("X-Account-Internal-Key");
        try {
            return ResponseEntity.ok(ApiResponse.ok(tokens.introspect(request.token(), presented)));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.fail(ErrorCode.AUTH_FORBIDDEN));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED));
        }
    }
}
