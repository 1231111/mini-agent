package com.miniagent.agent.permission;

import java.util.Locale;

/**
 * 会话级权限模式（对齐 Claude Code 分层思想，适配 Web 多用户）。
 *
 * <ul>
 *   <li>{@link #DEFAULT} — 正常执行面（现有 todo/意图门禁仍生效）</li>
 *   <li>{@link #PLAN} — 只读探索 + 写 todo；须批准计划后才放行写/执行类工具</li>
 *   <li>{@link #ACCEPT_EDITS} — 偏自动：跳过「危险工具二次询问」（仍受 todo 硬闸门约束）</li>
 *   <li>{@link #ASK} — 危险工具须前端确认一次后才放行</li>
 * </ul>
 */
public enum PermissionMode {
    DEFAULT,
    PLAN,
    ACCEPT_EDITS,
    ASK;

    public static PermissionMode from(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        String s = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (s) {
            case "plan" -> PLAN;
            case "accept_edits", "acceptedits", "accept" -> ACCEPT_EDITS;
            case "ask", "dont_ask_false" -> ASK;
            case "default", "normal" -> DEFAULT;
            default -> DEFAULT;
        };
    }

    public String wireName() {
        return switch (this) {
            case DEFAULT -> "default";
            case PLAN -> "plan";
            case ACCEPT_EDITS -> "accept_edits";
            case ASK -> "ask";
        };
    }

    public String labelZh() {
        return switch (this) {
            case DEFAULT -> "默认";
            case PLAN -> "Plan 模式";
            case ACCEPT_EDITS -> "自动编辑";
            case ASK -> "危险操作询问";
        };
    }
}
