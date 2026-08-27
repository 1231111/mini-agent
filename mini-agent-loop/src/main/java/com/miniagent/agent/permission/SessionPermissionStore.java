package com.miniagent.agent.permission;

import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 sessionId 保存权限模式、Plan 批准状态、Ask 一次放行集合、待办确认策略。
 */
@Component
public class SessionPermissionStore {

    public record SessionPerm(
            PermissionMode mode,
            boolean planApproved,
            Set<String> askGrantedTools,
            ConfirmPolicy confirmPolicy
    ) {}

    private final ConcurrentHashMap<String, SessionPerm> bySession = new ConcurrentHashMap<>();

    @PostConstruct
    void bindToContext() {
        PermissionContext.bindStore(this);
    }

    private static SessionPerm defaults() {
        return new SessionPerm(
                PermissionMode.DEFAULT, false,
                ConcurrentHashMap.newKeySet(), ConfirmPolicy.DANGEROUS);
    }

    public SessionPerm get(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return defaults();
        }
        return bySession.computeIfAbsent(sessionId, sid -> defaults());
    }

    public PermissionMode getMode(String sessionId) {
        return get(sessionId).mode();
    }

    public ConfirmPolicy getConfirmPolicy(String sessionId) {
        ConfirmPolicy p = get(sessionId).confirmPolicy();
        return p == null ? ConfirmPolicy.DANGEROUS : p;
    }

    public void setMode(String sessionId, PermissionMode mode) {
        if (StringUtils.isBlank(sessionId) || Objects.isNull(mode)) {
            return;
        }
        bySession.compute(sessionId, (k, old) -> {
            Set<String> grants = Objects.nonNull(old)
                    ? old.askGrantedTools() : ConcurrentHashMap.newKeySet();
            boolean approved = mode == PermissionMode.PLAN
                    ? (Objects.nonNull(old) && old.planApproved())
                    : true;
            if (mode == PermissionMode.PLAN
                    && (Objects.isNull(old) || old.mode() != PermissionMode.PLAN)) {
                approved = false;
            }
            ConfirmPolicy policy = Objects.nonNull(old) && old.confirmPolicy() != null
                    ? old.confirmPolicy() : ConfirmPolicy.DANGEROUS;
            return new SessionPerm(mode, approved, grants, policy);
        });
    }

    public void setConfirmPolicy(String sessionId, ConfirmPolicy policy) {
        if (StringUtils.isBlank(sessionId) || Objects.isNull(policy)) {
            return;
        }
        bySession.compute(sessionId, (k, old) -> {
            SessionPerm base = Objects.nonNull(old) ? old : defaults();
            return new SessionPerm(
                    base.mode(), base.planApproved(), base.askGrantedTools(), policy);
        });
    }

    /** 用户批准 Plan：保持 plan 模式但打开放行写工具；或切回 default */
    public void approvePlan(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return;
        }
        bySession.compute(sessionId, (k, old) -> {
            PermissionMode mode = Objects.nonNull(old) ? old.mode() : PermissionMode.PLAN;
            Set<String> grants = Objects.nonNull(old)
                    ? old.askGrantedTools() : ConcurrentHashMap.newKeySet();
            ConfirmPolicy policy = Objects.nonNull(old) && old.confirmPolicy() != null
                    ? old.confirmPolicy() : ConfirmPolicy.DANGEROUS;
            if (mode != PermissionMode.PLAN) {
                mode = PermissionMode.DEFAULT;
            }
            return new SessionPerm(mode, true, grants, policy);
        });
    }

    public boolean isPlanApproved(String sessionId) {
        SessionPerm p = get(sessionId);
        if (p.mode() != PermissionMode.PLAN) {
            return true;
        }
        return p.planApproved();
    }

    public void grantAskTool(String sessionId, String toolName) {
        if (Objects.isNull(sessionId) || StringUtils.isBlank(toolName)) {
            return;
        }
        SessionPerm p = get(sessionId);
        p.askGrantedTools().add(toolName.trim());
    }

    public boolean isAskGranted(String sessionId, String toolName) {
        if (Objects.isNull(toolName)) {
            return false;
        }
        return get(sessionId).askGrantedTools().contains(toolName);
    }

    public Map<String, Object> toView(String sessionId) {
        SessionPerm p = get(sessionId);
        ConfirmPolicy policy = p.confirmPolicy() == null
                ? ConfirmPolicy.DANGEROUS : p.confirmPolicy();
        return Map.of(
                "success", true,
                "sessionId", Optional.ofNullable(sessionId).orElse(""),
                "mode", p.mode().wireName(),
                "label", p.mode().labelZh(),
                "planApproved", p.planApproved(),
                "planActive", p.mode() == PermissionMode.PLAN && !p.planApproved(),
                "askGrantedTools", Set.copyOf(p.askGrantedTools()),
                "confirmPolicy", policy.wireName(),
                "confirmPolicyLabel", policy.labelZh()
        );
    }

    public void clear(String sessionId) {
        if (Objects.nonNull(sessionId)) {
            bySession.remove(sessionId);
        }
    }
}
