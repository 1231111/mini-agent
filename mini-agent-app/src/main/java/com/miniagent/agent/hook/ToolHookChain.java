package com.miniagent.agent.hook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 聚合 Pre/Post 工具钩子。
 */
@Slf4j
@Component
public class ToolHookChain {

    private final List<ToolHook> hooks;

    public ToolHookChain(@Autowired(required = false) List<ToolHook> hooks) {
        List<ToolHook> list = hooks == null ? List.of() : new ArrayList<>(hooks);
        list.sort(Comparator.comparingInt(ToolHook::order));
        this.hooks = List.copyOf(list);
        if (!this.hooks.isEmpty()) {
            log.info("ToolHook 已加载 {} 个: {}", this.hooks.size(),
                    this.hooks.stream().map(ToolHook::name).toList());
        }
    }

    public ToolPreDecision before(ToolHookContext context) {
        String args = context.argumentsJson();
        for (ToolHook hook : hooks) {
            try {
                ToolPreDecision d = hook.before(new ToolHookContext(
                        context.sessionId(), context.toolName(), args,
                        context.turn(), context.subagent()));
                if (d == null) continue;
                if (d.deny()) {
                    log.info("ToolHook [{}] 拒绝 {}: {}", hook.name(), context.toolName(), d.denyMessage());
                    return d;
                }
                if (d.argumentsJson() != null) args = d.argumentsJson();
            } catch (Exception e) {
                log.warn("ToolHook [{}] before 异常，忽略: {}", hook.name(), e.getMessage());
            }
        }
        return ToolPreDecision.proceed(args);
    }

    public String after(ToolHookContext context, String result) {
        String r = result;
        for (ToolHook hook : hooks) {
            try {
                String next = hook.after(context, r);
                if (next != null) r = next;
            } catch (Exception e) {
                log.warn("ToolHook [{}] after 异常，忽略: {}", hook.name(), e.getMessage());
            }
        }
        return r;
    }

    public int size() {
        return hooks.size();
    }
}
