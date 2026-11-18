package com.miniagent.web;

import com.miniagent.common.ApiResponse;
import com.miniagent.agent.execution.ActionJournal;
import com.miniagent.agent.execution.ActionJournalEntry;
import com.miniagent.agent.execution.ActionExecutionStatus;
import com.miniagent.web.dto.resp.RevokedSessionsDTO;
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

/** System administrator governance API. SecurityConfig restricts this route to SYSTEM_ADMIN. */
@RestController
@RequestMapping(value = "/api/admin", produces = MediaType.APPLICATION_JSON_VALUE)
public class SystemAdminController {
    @Autowired
    private  SystemAdminService service;

    @Autowired
    private CurrentUser currentUser;

    @Autowired
    private ActionJournal actionJournal;


    @GetMapping("/tenants")
    public ApiResponse<?> tenants() {
        return ApiResponse.ok(service.listTenants());
    }

    @PostMapping(value = "/tenants", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<?> createTenant(@Valid @RequestBody TenantCreateRequest body) {
        return ApiResponse.ok(service.createTenant(actor(), body.slug(), body.name(),
                body.dailyTokenLimit(), body.dailyUserTokenLimit()));
    }

    @PatchMapping(value = "/tenants/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<?> updateTenant(@PathVariable Long id,
                                       @Valid @RequestBody TenantUpdateRequest body) {
        return ApiResponse.ok(service.updateTenant(actor(), id, body.enabled(),
                body.dailyTokenLimit(), body.dailyUserTokenLimit()));
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

    @PatchMapping(value = "/users/{id}/quota", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<?> updateUserQuota(@PathVariable Long id,
                                          @Valid @RequestBody UserQuotaUpdateRequest body) {
        return ApiResponse.ok(service.updateUserQuota(actor(), id, body.dailyTokenLimitOverride()));
    }

    @PostMapping(value = "/users/{id}/reset-password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<?> resetPassword(@PathVariable Long id,
                                        @Valid @RequestBody PasswordResetRequest body) {
        return ApiResponse.ok(service.resetPassword(actor(), id, body.password()));
    }

    @PostMapping("/users/{id}/revoke-sessions")
    public ApiResponse<RevokedSessionsDTO> revokeSessions(@PathVariable Long id) {
        return ApiResponse.ok(new RevokedSessionsDTO(service.revokeUserSessions(actor(), id)));
    }

    @GetMapping("/audit")
    public ApiResponse<?> audit(@RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.ok(service.listAudit(page, size));
    }

    /** Unknown external side effects require a human reconciliation; never replay them automatically. */
    @GetMapping("/action-journal/unresolved")
    public ApiResponse<?> unresolvedActions(@RequestParam(defaultValue = "100") int limit,
                                            @RequestParam(defaultValue = "0") int offset) {
        int bounded = Math.max(1, Math.min(200, limit));
        int boundedOffset = Math.max(0, Math.min(1_000_000, offset));
        return ApiResponse.ok(actionJournal.unresolved(bounded, boundedOffset).stream()
                .map(UnresolvedActionView::of).toList());
    }

    /** Explicitly reconcile a durable UNKNOWN/RUNNING receipt; never executes it. */
    @PostMapping(value = "/action-journal/unresolved/{receiptId}:reconcile",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<?> reconcileAction(@PathVariable String receiptId,
                                          @Valid @RequestBody ReconcileActionRequest body) {
        ActionExecutionStatus resolution = ActionExecutionStatus.valueOf(body.resolution().toUpperCase());
        ActionJournalEntry entry = actionJournal.reconcile(receiptId, resolution, body.note())
                .orElseThrow(() -> new IllegalArgumentException("action receipt is no longer unresolved"));
        service.auditActionJournalReview(actor(), receiptId, resolution.name(), body.note());
        return ApiResponse.ok(UnresolvedActionView.of(entry));
    }

    private Long actor() {
        return currentUser.userId();
    }

    public record TenantCreateRequest(
            @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{2,63}") String slug,
            @NotBlank @Size(max = 120) String name,
            @PositiveOrZero Long dailyTokenLimit,
            @PositiveOrZero Long dailyUserTokenLimit) {}

    public record TenantUpdateRequest(Boolean enabled,
                                      @PositiveOrZero Long dailyTokenLimit,
                                      @PositiveOrZero Long dailyUserTokenLimit) {}

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

    /** null inherits from membership then tenant default; zero means this user is unlimited. */
    public record UserQuotaUpdateRequest(@PositiveOrZero Long dailyTokenLimitOverride) {}

    public record PasswordResetRequest(
            @NotBlank @Size(min = 12, max = 200) String password) {}

    public record ReconcileActionRequest(
            @NotBlank @Pattern(regexp = "(?i)FAILED|CANCELLED") String resolution,
            @Size(max = 1000) String note) {}

    public record UnresolvedActionView(String receiptId, String sessionIdHash, String toolName, String argumentsHash,
                                       String status, int attempt, long timestampEpochMillis,
                                       String errorCode) {
        static UnresolvedActionView of(ActionJournalEntry entry) {
            String receiptId = entry.receiptId().isBlank() ? digestKey(entry.key()) : entry.receiptId();
            String sessionIdHash = entry.sessionIdHash().isBlank() ? null : entry.sessionIdHash();
            return new UnresolvedActionView(receiptId, sessionIdHash,
                    entry.toolName(), entry.argumentsHash(), entry.status().name(), entry.attempt(),
                    entry.timestampEpochMillis(), entry.errorCode());
        }

        private static String digestKey(com.miniagent.agent.execution.ActionJournalKey key) {
            try {
                java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
                for (String value : java.util.List.of(key.sessionId(), key.planVersion(), key.nodeId(), key.idempotencyKey())) {
                    byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                    digest.update(bytes);
                }
                return java.util.HexFormat.of().formatHex(digest.digest());
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 unavailable", impossible);
            }
        }
    }
}
