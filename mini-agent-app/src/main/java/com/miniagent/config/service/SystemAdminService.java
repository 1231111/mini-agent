package com.miniagent.config.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.config.entity.AdminAuditLog;
import com.miniagent.config.entity.Tenant;
import com.miniagent.config.entity.User;
import com.miniagent.config.entity.UserRole;
import com.miniagent.config.repository.AdminAuditLogRepository;
import com.miniagent.config.repository.AuthSessionRepository;
import com.miniagent.config.repository.TenantRepository;
import com.miniagent.config.repository.UserRepository;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** System-level tenant, user and session governance with safety invariants and audit. */
@Service
public class SystemAdminService {
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{2,49}");
    private static final Pattern TENANT_SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{2,63}");

    @Autowired
    private  TenantRepository tenants;
    @Autowired
    private  UserRepository users;
    @Autowired
    private  AuthSessionRepository sessions;
    @Autowired
    private  AdminAuditLogRepository auditLogs;
    @Autowired
    private  PasswordEncoder passwordEncoder;

    private  ObjectMapper objectMapper;

    @Value("${agent.auth.password-min-length:12}")
    private int passwordMinLength;

    @Autowired
    public SystemAdminService(TenantRepository tenants,
                              UserRepository users,
                              AuthSessionRepository sessions,
                              AdminAuditLogRepository auditLogs,
                              PasswordEncoder passwordEncoder,
                              ObjectMapper objectMapper) {
        this.tenants = tenants;
        this.users = users;
        this.sessions = sessions;
        this.auditLogs = auditLogs;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }



    @Transactional(readOnly = true)
    public List<Map<String, Object>> listTenants() {
        return tenants.findAll(Sort.by(Sort.Direction.ASC, "id"))
                .stream().map(this::tenantView).toList();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listUsers(Long tenantId) {
        return users.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                .filter(user -> tenantId == null || Objects.equals(tenantId, user.getTenantId()))
                .map(this::userView).toList();
    }

    @Transactional
    public Map<String, Object> createTenant(Long actorId, String slug, String name,
                                            Long dailyTokenLimit) {
        String normalizedSlug = StringUtils.trimToEmpty(slug).toLowerCase(Locale.ROOT);
        String normalizedName = StringUtils.trimToEmpty(name);
        if (!TENANT_SLUG.matcher(normalizedSlug).matches()) {
            throw new IllegalArgumentException("slug must be 3-64 lowercase letters, digits or hyphens");
        }
        if (normalizedName.isEmpty() || normalizedName.length() > 120) {
            throw new IllegalArgumentException("name must be 1-120 characters");
        }
        long limit = dailyTokenLimit == null ? 0 : dailyTokenLimit;
        if (limit < 0) {
            throw new IllegalArgumentException("dailyTokenLimit must be >= 0");
        }
        if (tenants.findBySlug(normalizedSlug).isPresent()) {
            throw new IllegalStateException("tenant slug already exists");
        }

        Tenant tenant = new Tenant();
        tenant.setSlug(normalizedSlug);
        tenant.setName(normalizedName);
        tenant.setEnabled(true);
        tenant.setDailyTokenLimit(limit);
        try {
            tenant = tenants.saveAndFlush(tenant);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("tenant slug already exists", e);
        }
        audit(actorId, "TENANT_CREATE", "TENANT", tenant.getId(), tenant.getId(),
                details("slug", tenant.getSlug(), "dailyTokenLimit", limit));
        return tenantView(tenant);
    }

    @Transactional
    public Map<String, Object> updateTenant(Long actorId, Long tenantId, Boolean enabled,
                                            Long dailyTokenLimit) {
        // Lock administrator rows first so concurrent governance changes preserve the invariant.
        List<User> admins = users.findByRoleForUpdate(UserRole.SYSTEM_ADMIN);
        Tenant tenant = tenants.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("tenant not found"));
        if (Boolean.FALSE.equals(enabled) && tenant.isEnabled()
                && !hasEffectiveAdminOutside(admins, tenantId)) {
            throw new IllegalStateException("不能禁用承载最后一个可用系统管理员的租户");
        }
        if (dailyTokenLimit != null && dailyTokenLimit < 0) {
            throw new IllegalArgumentException("dailyTokenLimit must be >= 0");
        }

        boolean disabling = Boolean.FALSE.equals(enabled) && tenant.isEnabled();
        if (enabled != null) {
            tenant.setEnabled(enabled);
        }
        if (dailyTokenLimit != null) {
            tenant.setDailyTokenLimit(dailyTokenLimit);
        }
        Tenant saved = tenants.save(tenant);
        int revoked = disabling ? sessions.revokeAllForTenant(tenantId) : 0;
        audit(actorId, "TENANT_UPDATE", "TENANT", tenantId, tenantId,
                details("enabled", saved.isEnabled(), "dailyTokenLimit", saved.getDailyTokenLimit(),
                        "sessionsRevoked", revoked));
        return tenantView(saved);
    }

    @Transactional
    public Map<String, Object> createUser(Long actorId, Long tenantId, String username,
                                          String password, String displayName, String role) {
        Tenant tenant = tenants.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("tenant not found"));
        if (!tenant.isEnabled()) {
            throw new IllegalArgumentException("cannot create user in disabled tenant");
        }
        String normalized = requireUsername(username);
        requirePassword(password);
        UserRole parsedRole = parseRole(role, UserRole.USER);
        if (users.existsByUsername(normalized)) {
            throw new IllegalStateException("username already exists");
        }

        User user = new User();
        user.setTenantId(tenantId);
        user.setUsername(normalized);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setDisplayName(normalizeDisplayName(displayName, normalized));
        user.setRole(parsedRole);
        user.setEnabled(true);
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("username already exists", e);
        }
        audit(actorId, "USER_CREATE", "USER", user.getId(), tenantId,
                details("username", user.getUsername(), "role", user.getRole().name()));
        return userView(user);
    }

    @Transactional
    public Map<String, Object> updateUser(Long actorId, Long userId, Boolean enabled,
                                          String role, String displayName) {
        List<User> admins = users.findByRoleForUpdate(UserRole.SYSTEM_ADMIN);
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        UserRole nextRole = parseRole(role, user.getRole());
        boolean nextEnabled = enabled == null ? user.isEnabled() : enabled;
        boolean roleChanged = nextRole != user.getRole();
        boolean enabledChanged = nextEnabled != user.isEnabled();

        if (user.getRole() == UserRole.SYSTEM_ADMIN
                && (nextRole != UserRole.SYSTEM_ADMIN || !nextEnabled)
                && !hasOtherEffectiveAdmin(admins, user.getId())) {
            throw new IllegalStateException("不能禁用或降级最后一个可用系统管理员");
        }

        user.setRole(nextRole);
        user.setEnabled(nextEnabled);
        if (displayName != null) {
            user.setDisplayName(normalizeDisplayName(displayName, user.getUsername()));
        }
        User saved = users.save(user);
        int revoked = (!saved.isEnabled() || roleChanged || enabledChanged)
                ? sessions.revokeAllForUser(saved.getId()) : 0;
        audit(actorId, "USER_UPDATE", "USER", saved.getId(), saved.getTenantId(),
                details("enabled", saved.isEnabled(), "role", saved.getRole().name(),
                        "sessionsRevoked", revoked));
        return userView(saved);
    }

    @Transactional
    public Map<String, Object> resetPassword(Long actorId, Long userId, String password) {
        requirePassword(password);
        User user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        user.setPasswordHash(passwordEncoder.encode(password));
        users.save(user);
        int revoked = sessions.revokeAllForUser(userId);
        audit(actorId, "USER_PASSWORD_RESET", "USER", userId, user.getTenantId(),
                details("sessionsRevoked", revoked));
        return userView(user);
    }

    @Transactional
    public int revokeUserSessions(Long actorId, Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
        int revoked = sessions.revokeAllForUser(userId);
        audit(actorId, "USER_SESSIONS_REVOKE", "USER", userId, user.getTenantId(),
                details("sessionsRevoked", revoked));
        return revoked;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> listAudit(int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(200, size));
        Page<AdminAuditLog> result = auditLogs.findAll(PageRequest.of(
                safePage, safeSize, Sort.by(Sort.Direction.DESC, "id")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", result.getContent().stream().map(this::auditView).toList());
        out.put("page", result.getNumber());
        out.put("size", result.getSize());
        out.put("totalElements", result.getTotalElements());
        out.put("totalPages", result.getTotalPages());
        return out;
    }

    /** Adds privileged changes from other administrator modules to the same audit stream. */
    @Transactional
    public void recordAudit(Long actorId, String action, String targetType,
                            Object targetId, Long tenantId, Map<String, Object> details) {
        audit(actorId, action, targetType, targetId, tenantId,
                details == null ? Map.of() : details);
    }

    private boolean hasEffectiveAdminOutside(List<User> admins, Long excludedTenant) {
        return admins.stream().anyMatch(admin -> admin.isEnabled()
                && !Objects.equals(admin.getTenantId(), excludedTenant)
                && tenantEnabled(admin.getTenantId()));
    }

    private boolean hasOtherEffectiveAdmin(List<User> admins, Long excludedUser) {
        return admins.stream().anyMatch(admin -> !Objects.equals(admin.getId(), excludedUser)
                && admin.isEnabled() && tenantEnabled(admin.getTenantId()));
    }

    private boolean tenantEnabled(Long tenantId) {
        return tenantId != null && tenants.findById(tenantId).map(Tenant::isEnabled).orElse(false);
    }

    private String requireUsername(String username) {
        String normalized = StringUtils.trimToEmpty(username);
        if (!USERNAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("username must be 3-50 safe characters");
        }
        return normalized;
    }

    private void requirePassword(String password) {
        int minimum = Math.max(12, passwordMinLength);
        if (password == null || password.length() < minimum || password.length() > 200) {
            throw new IllegalArgumentException("password must be " + minimum + "-200 characters");
        }
    }

    private static UserRole parseRole(String role, UserRole fallback) {
        if (StringUtils.isBlank(role)) {
            return fallback;
        }
        try {
            return UserRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown role", e);
        }
    }

    private static String normalizeDisplayName(String displayName, String fallback) {
        return StringUtils.abbreviate(StringUtils.defaultIfBlank(displayName, fallback).trim(), 100);
    }

    private void audit(Long actorId, String action, String targetType, Object targetId,
                       Long tenantId, Map<String, Object> details) {
        if (actorId == null) {
            throw new IllegalStateException("authenticated audit actor required");
        }
        AdminAuditLog entry = new AdminAuditLog();
        entry.setActorUserId(actorId);
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(String.valueOf(targetId));
        entry.setTenantId(tenantId);
        entry.setRequestId(StringUtils.abbreviate(MDC.get("requestId"), 80));
        try {
            entry.setDetailsJson(objectMapper.writeValueAsString(details));
        } catch (Exception e) {
            throw new IllegalStateException("unable to serialize admin audit record", e);
        }
        auditLogs.save(entry);
    }

    private Map<String, Object> tenantView(Tenant tenant) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", tenant.getId());
        out.put("slug", tenant.getSlug());
        out.put("name", tenant.getName());
        out.put("enabled", tenant.isEnabled());
        out.put("dailyTokenLimit", tenant.getDailyTokenLimit());
        out.put("userCount", users.countByTenantId(tenant.getId()));
        return out;
    }

    private Map<String, Object> userView(User user) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", user.getId());
        out.put("username", user.getUsername());
        out.put("displayName", user.getDisplayName());
        out.put("tenantId", user.getTenantId());
        out.put("role", user.getRole());
        out.put("enabled", user.isEnabled());
        return out;
    }

    private Map<String, Object> auditView(AdminAuditLog entry) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", entry.getId());
        out.put("actorUserId", entry.getActorUserId());
        out.put("action", entry.getAction());
        out.put("targetType", entry.getTargetType());
        out.put("targetId", entry.getTargetId());
        out.put("tenantId", entry.getTenantId());
        out.put("requestId", entry.getRequestId());
        out.put("createdAt", entry.getCreatedAt());
        try {
            out.put("details", objectMapper.readValue(
                    StringUtils.defaultIfBlank(entry.getDetailsJson(), "{}"),
                    new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            out.put("details", Map.of());
        }
        return out;
    }

    private static Map<String, Object> details(Object... values) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            out.put(String.valueOf(values[i]), values[i + 1]);
        }
        return out;
    }
}
