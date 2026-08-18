package com.miniagent.agent.intent;

/** 最终意图决定的来源，供校准、审计和离线评测使用。 */
public enum IntentDecisionSource {
    RULE,
    DEDICATED_MODEL,
    FALLBACK_MODEL,
    HEURISTIC,
    CLARIFICATION
}
