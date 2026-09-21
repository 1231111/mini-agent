package com.miniagent.agent.context;

import org.apache.commons.lang3.StringUtils;

/**
 * 一个上下文贡献块。空 text 在组装时丢弃。
 */
public record ContextFragment(ContextSlot slot, String text) {

    public boolean hasText() {
        return StringUtils.isNotBlank(text);
    }

    public static ContextFragment of(ContextSlot slot, String text) {
        return new ContextFragment(slot, text == null ? "" : text);
    }
}
