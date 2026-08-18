package com.miniagent.agent.intent;

import java.util.Locale;

/** 意图进入执行链路前的业务风险分级。 */
public enum IntentRiskLevel {
    LOW,
    MEDIUM,
    HIGH;

    public static IntentRiskLevel parse(String raw) {
        if (raw == null || raw.isBlank()) return MEDIUM;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return MEDIUM;
        }
    }

    public static IntentRiskLevel max(IntentRiskLevel left, IntentRiskLevel right) {
        IntentRiskLevel a = left == null ? MEDIUM : left;
        IntentRiskLevel b = right == null ? MEDIUM : right;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
