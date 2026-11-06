package com.miniagent.agent.context;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 按注册顺序收集贡献者，经预算管理器拼成 system prompt。
 */
@Component
public class ContextBuilder {

    private final List<SystemContextContributor> contributors;
    private final ContextBudgetManager budgetManager;

    public ContextBuilder(List<SystemContextContributor> contributors,
                          ContextBudgetManager budgetManager) {
        this.contributors = contributors == null ? List.of() : contributors;
        this.budgetManager = budgetManager;
    }

    public String build(ContextBuildContext context) {
        List<ContextFragment> fragments = new ArrayList<>();
        for (SystemContextContributor contributor : contributors) {
            ContextFragment fragment = contributor.contribute(context);
            if (fragment != null && fragment.hasText()) {
                fragments.add(fragment);
            }
        }
        return budgetManager.assemble(fragments);
    }
}
