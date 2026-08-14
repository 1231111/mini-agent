package com.miniagent.agent.permission;

import java.util.Locale;
import org.apache.commons.lang3.StringUtils;

/**
 * 待办关键步是否等人确认（与 {@link PermissionMode#ASK} 的工具询问分开）。
 *
 * <ul>
 *   <li>{@link #AUTO} — 不闸门，直接推进</li>
 *   <li>{@link #DANGEROUS} — 仅上线/删除等危险步等待确认</li>
 * </ul>
 */
public enum ConfirmPolicy {
    AUTO,
    DANGEROUS;

    public static ConfirmPolicy from(String raw) {
        if (StringUtils.isBlank(raw)) {
            return DANGEROUS;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (s) {
            case "auto", "automatic" -> AUTO;
            case "dangerous", "danger", "confirm" -> DANGEROUS;
            default -> DANGEROUS;
        };
    }

    public String wireName() {
        return switch (this) {
            case AUTO -> "auto";
            case DANGEROUS -> "dangerous";
        };
    }

    public String labelZh() {
        return switch (this) {
            case AUTO -> "自动确认";
            case DANGEROUS -> "危险操作确认";
        };
    }
}
