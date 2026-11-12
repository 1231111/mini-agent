package com.miniagent.config.model;

import com.miniagent.agent.core.ToolVisionModelResolver;
import com.miniagent.common.context.OwnerContext;
import org.springframework.stereotype.Component;

/** Uses the same owner-aware vision preset as user-uploaded images. */
@Component
public class ConfiguredToolVisionModelResolver implements ToolVisionModelResolver {
    private final ModelClientFactory modelClientFactory;

    public ConfiguredToolVisionModelResolver(ModelClientFactory modelClientFactory) {
        this.modelClientFactory = modelClientFactory;
    }

    @Override
    public Models resolve() {
        Long userId = OwnerContext.currentUserId();
        if (userId == null) throw new IllegalStateException("Vision observations require an authenticated task owner");
        var resolved = modelClientFactory.resolveForMultimodal(userId);
        return new Models(resolved.chat(), resolved.streaming(), resolved.settings().contextWindowTokens());
    }
}
