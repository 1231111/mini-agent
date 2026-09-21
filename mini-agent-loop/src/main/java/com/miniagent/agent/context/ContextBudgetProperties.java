package com.miniagent.agent.context;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * agent.context.budget.* — system prompt 槽位 token 上限。
 */
@ConfigurationProperties(prefix = "agent.context.budget")
public class ContextBudgetProperties {

    private boolean enabled = true;
    /** 中英混合粗算：字符数 ≈ token × 该系数。 */
    private double charsPerToken = 2.0;
    private Map<String, Integer> slotTokens = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getCharsPerToken() {
        return charsPerToken;
    }

    public void setCharsPerToken(double charsPerToken) {
        this.charsPerToken = charsPerToken;
    }

    public Map<String, Integer> getSlotTokens() {
        return slotTokens;
    }

    public void setSlotTokens(Map<String, Integer> slotTokens) {
        this.slotTokens = slotTokens == null ? new LinkedHashMap<>() : slotTokens;
    }

    int tokenBudget(ContextSlot slot) {
        Integer override = slotTokens.get(slot.configKey());
        if (override != null && override > 0) {
            return override;
        }
        return slot.defaultTokenBudget();
    }

    int maxChars(ContextSlot slot) {
        double factor = charsPerToken > 0 ? charsPerToken : 2.0;
        return (int) Math.max(32, Math.round(tokenBudget(slot) * factor));
    }

    static String clip(String text, int maxChars) {
        if (StringUtils.isBlank(text) || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…";
    }
}
