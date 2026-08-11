package com.miniagent.agent.permission;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 sessionId 保存权限模式、Plan 批准状态、Ask 一次放行集合。
 */
@Component
public class SessionPermissionStore {

    public record SessionPerm(
            PermissionMode mode,
            boolean planApproved,
            Set<String> askGrantedTools
    ) {}

    private final ConcurrentHashMap<String, SessionPerm> bySession = new ConcurrentHashMap<>();

    public SessionPerm get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new SessionPerm(PermissionMode.DEFAULT, false, ConcurrentHashMap.newKeySet());
        }
        return bySession.computeIfAbsent(sessionId, sid ->
                new SessionPerm(PermissionMode.DEFAULT, false, ConcurrentHashMap.newKeySet()));
    }

    public PermissionMode getMode(String sessionId) {
        return get(sessionId).mode();
    }

    public void setMode(String sessionId, PermissionMode mode) {
        if (sessionId == null || sessionId.isBlank() || mode == null) return;
        bySession.compute(sessionId, (k, old) -> {
            Set<String> grants = old != null ? old.askGrantedTools() : ConcurrentHashMap.newKeySet();
            boolean approved = mode == PermissionMode.PLAN
                    ? (old != null && old.planApproved())
                    : true; // 非 plan 视为无需 plan 闸门
            if (mode == PermissionMode.PLAN && (old == null || old.mode() != PermissionMode.PLAN)) {
                approved = false; // 切入 plan 时重置批准
            }
            return new SessionPerm(mode, approved, grants);
        });
    }

    /** 用户批准 Plan：保持 plan 模式但打开放行写工具；或切回 default */
    public void approvePlan(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        bySession.compute(sessionId, (k, old) -> {
            PermissionMode mode = old != null ? old.mode() : PermissionMode.PLAN;
            Set<String> grants = old != null ? old.askGrantedTools() : ConcurrentHashMap.newKeySet();
            if (mode != PermissionMode.PLAN) mode = PermissionMode.DEFAULT;
            return new SessionPerm(mode, true, grants);
        });
    }

    public boolean isPlanApproved(String sessionId) {
        SessionPerm p = get(sessionId);
        if (p.mode() != PermissionMode.PLAN) return true;
        return p.planApproved();
    }

    public void grantAskTool(String sessionId, String toolName) {
        if (sessionId == null || toolName == null || toolName.isBlank()) return;
        SessionPerm p = get(sessionId);
        p.askGrantedTools().add(toolName.trim());
    }

    public boolean isAskGranted(String sessionId, String toolName) {
        if (toolName == null) return false;
        return get(sessionId).askGrantedTools().contains(toolName);
    }

    public Map<String, Object> toView(String sessionId) {
        SessionPerm p = get(sessionId);
        return Map.of(
                "success", true,
                "sessionId", sessionId == null ? "" : sessionId,
                "mode", p.mode().wireName(),
                "label", p.mode().labelZh(),
                "planApproved", p.planApproved(),
                "planActive", p.mode() == PermissionMode.PLAN && !p.planApproved(),
                "askGrantedTools", Set.copyOf(p.askGrantedTools())
        );
    }

    public void clear(String sessionId) {
        if (sessionId != null) bySession.remove(sessionId);
    }
}
