package com.miniagent.agent.intent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * 生产链路使用的结构化意图决定。前七个字段是稳定决策契约，后续字段用于兼容现有 TaskPlan。
 */
public record IntentDecision(
        IntentType intent,
        double confidence,
        List<IntentAlternative> alternatives,
        boolean needPlanning,
        boolean needClarification,
        List<String> requiredCapabilities,
        IntentRiskLevel riskLevel,
        String taskGoal,
        boolean needsWeb,
        boolean needsFiles,
        boolean needsImageGen,
        boolean shouldUseHistory,
        String toolProfile,
        String reason,
        IntentDecisionSource source
) {
    public IntentDecision {
        IntentType normalizedIntent = intent == null ? IntentType.UNKNOWN : intent;
        intent = normalizedIntent;
        confidence = clamp(confidence);
        alternatives = alternatives == null ? List.of() : alternatives.stream()
                .filter(a -> a != null && a.intent() != normalizedIntent)
                .limit(3)
                .toList();
        requiredCapabilities = normalizeCapabilities(requiredCapabilities);
        riskLevel = riskLevel == null ? IntentRiskLevel.MEDIUM : riskLevel;
        taskGoal = taskGoal == null ? "" : taskGoal.trim();
        toolProfile = normalizeProfile(toolProfile);
        reason = reason == null ? "" : reason.trim();
        source = source == null ? IntentDecisionSource.HEURISTIC : source;
    }

    public IntentDecision withConfidence(double value) {
        return copy(value, needClarification, reason, source);
    }

    public IntentDecision withClarification(boolean value, String why) {
        String nextReason = why == null || why.isBlank() ? reason : why.trim();
        return copy(confidence, value, nextReason,
                value ? IntentDecisionSource.CLARIFICATION : source);
    }

    public IntentDecision withSource(IntentDecisionSource value) {
        return copy(confidence, needClarification, reason, value);
    }

    public double alternativeMargin() {
        if (alternatives.isEmpty()) return 1.0;
        return confidence - alternatives.get(0).confidence();
    }

    private IntentDecision copy(double nextConfidence, boolean clarification,
                                String nextReason, IntentDecisionSource nextSource) {
        return new IntentDecision(intent, nextConfidence, alternatives, needPlanning,
                clarification, requiredCapabilities, riskLevel, taskGoal,
                needsWeb, needsFiles, needsImageGen, shouldUseHistory,
                toolProfile, nextReason, nextSource);
    }

    private static List<String> normalizeCapabilities(List<String> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String capability : raw) {
            if (capability == null || capability.isBlank()) continue;
            out.add(capability.trim().toLowerCase(Locale.ROOT)
                    .replace('-', '_').replace(' ', '_'));
        }
        return List.copyOf(out);
    }

    private static String normalizeProfile(String raw) {
        String value = raw == null ? "FULL" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "QUESTION", "IMAGE", "FULL" -> value;
            default -> "FULL";
        };
    }

    private static double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }
}
