package com.miniagent.web;

import com.miniagent.common.ApiResponse;
import com.miniagent.config.security.CurrentUser;
import com.miniagent.config.service.SystemAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** System administrator governance API. SecurityConfig restricts this route to SYSTEM_ADMIN. */
@RestController
@RequestMapping(value = "/api/admin", produces = MediaType.APPLICATION_JSON_VALUE)
public class SystemAdminController {
    @Autowired
    private  SystemAdminService service;

    private CurrentUser currentUser;


    @GetMapping("/tenants")
    public ApiResponse<?> tenants() {
        return ApiResponse.ok(service.listTenants());
    }

    @PostMapping(value = "/tenants", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<?> createTenant(@Valid @RequestBody TenantCreateRequest body) {
        return ApiResponse.ok(service.createTenant(actor(), body.slug(), body.name(),
                body.dailyTokenLimit()));
    }

    @PatchMapping(value = "/tenants/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<?> updateTenant(@PathVariable Long id,
                                       @Valid @RequestBody TenantUpdateRequest body) {
        return ApiResponse.ok(service.updateTenant(actor(), id, body.enabled(),
                body.dailyTokenLimit()));
    }

    @GetMapping("/users")
    public ApiResponse<?> users(@RequestParam(required = false) Long tenantId) {
        return ApiResponse.ok(service.listUsers(tenantId));
    }

    @PostMapping(value = "/users", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<?> createUser(@Valid @RequestBody UserCreateRequest body) {
        return ApiResponse.ok(service.createUser(actor(), body.tenantId(), body.username(),
                body.password(), body.displayName(), body.role()));
    }

    @PatchMapping(value = "/users/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<?> updateUser(@PathVariable Long id,
                                     @Valid @RequestBody UserUpdateRequest body) {
        return ApiResponse.ok(service.updateUser(actor(), id, body.enabled(), body.role(),
                body.displayName()));
    }

    @PostMapping(value = "/users/{id}/reset-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<?> resetPassword(@PathVariable Long id,
                                        @Valid @RequestBody PasswordResetRequest body) {
        return ApiResponse.ok(service.resetPassword(actor(), id, body.password()));
    }

    @PostMapping("/users/{id}/revoke-sessions")
    public ApiResponse<?> revokeSessions(@PathVariable Long id) {
        return ApiResponse.ok(Map.of("revoked", service.revokeUserSessions(actor(), id)));
    }

    @GetMapping("/audit")
    public ApiResponse<?> audit(@RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.ok(service.listAudit(page, size));
    }

    private Long actor() {
        return currentUser.userId();
    }

    public record TenantCreateRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{2,63}") String slug,
            @NotBlank @Size(max = 120) String name,
            @PositiveOrZero Long dailyTokenLimit) {}

    public record TenantUpdateRequest(Boolean enabled,
                                      @PositiveOrZero Long dailyTokenLimit) {}

    public record UserCreateRequest(
            @NotNull Long tenantId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9_.-]{2,49}") String username,
            @NotBlank @Size(min = 12, max = 200) String password,
            @Size(max = 100) String displayName,
            @Pattern(regexp = "(?i)(USER|TENANT_ADMIN|SYSTEM_ADMIN)") String role) {}

    public record UserUpdateRequest(
            Boolean enabled,
            @Pattern(regexp = "(?i)(USER|TENANT_ADMIN|SYSTEM_ADMIN)") String role,
            @Size(max = 100) String displayName) {}

    public record PasswordResetRequest(
            @NotBlank @Size(min = 12, max = 200) String password) {}
}
