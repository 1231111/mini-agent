package com.miniagent.agent.context;

import java.util.List;

/** Final prompt plus the exact, budget-fitted fragments that produced it. */
public record ContextBuildResult(String systemPrompt, List<ContextFragment> fragments) {
    public ContextBuildResult {
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        fragments = fragments == null ? List.of() : List.copyOf(fragments);
    }

    public String textFor(ContextSlot slot) {
        return fragments.stream()
                .filter(fragment -> fragment != null && fragment.slot() == slot)
                .map(ContextFragment::text)
                .findFirst().orElse("");
    }
}
