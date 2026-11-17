package com.miniagent.agent.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

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
        return assembleFitted(fit(fragments));
    }

    /** Apply exactly the same slot budgets used by the prompt assembler. */
    public List<ContextFragment> fit(List<ContextFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) return List.of();
        List<ContextFragment> fitted = new ArrayList<>();
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
                fitted.add(ContextFragment.of(fragment.slot(), text));
            }
        }
        return List.copyOf(fitted);
    }

    public String assembleFitted(List<ContextFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) return "";
        StringJoiner joined = new StringJoiner("\n\n");
        for (ContextFragment fragment : fragments) {
            if (fragment != null && fragment.hasText()) joined.add(fragment.text());
        }
        return joined.toString();
    }
}
