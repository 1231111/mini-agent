package com.miniagent.agent.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 按槽位截断后拼接 system prompt。不精确分词；Loop 内压缩仍由 ContextCompressor 负责。
 */
@Component
public class ContextBudgetManager {

    private final ContextBudgetProperties properties;

    public ContextBudgetManager(ContextBudgetProperties properties) {
        this.properties = properties;
    }

    public String assemble(List<ContextFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (ContextFragment fragment : fragments) {
            if (fragment == null || !fragment.hasText()) {
                continue;
            }
            String text = fragment.text();
            if (properties.isEnabled()) {
                text = ContextBudgetProperties.clip(
                        text, properties.maxChars(fragment.slot()));
            }
            if (!text.isBlank()) {
                parts.add(text);
            }
        }
        return String.join("\n\n", parts);
    }
}
