package com.miniagent.agent.intent;

/** 次优意图及其置信度，不能再把不确定性压成单一枚举。 */
public record IntentAlternative(IntentType intent, double confidence, String reason) {
    public IntentAlternative {
        intent = intent == null ? IntentType.UNKNOWN : intent;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        reason = reason == null ? "" : reason.trim();
    }
}
