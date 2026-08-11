package com.miniagent.agent.hook;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 聚合全部 {@link StopHook}；任一非 PROCEED 即短路。
 */
@Slf4j
@Component
public class StopHookChain {

    private final List<StopHook> hooks;

    public StopHookChain(@Autowired(required = false) List<StopHook> hooks) {
        List<StopHook> list = Objects.isNull(hooks) ? List.of() : new ArrayList<>(hooks);
        list.sort(Comparator.comparingInt(StopHook::order));
        this.hooks = List.copyOf(list);
        if (!this.hooks.isEmpty()) {
            log.info("StopHook 已加载 {} 个: {}", this.hooks.size(),
                    this.hooks.stream().map(StopHook::name).toList());
        }
    }

    public StopDecision evaluate(StopContext context) {
        if (hooks.isEmpty()) return StopDecision.proceed();
        for (StopHook hook : hooks) {
            try {
                StopDecision d = hook.evaluate(context);
                if (Objects.isNull(d) || d.isProceed()) continue;
                log.info("StopHook [{}] 裁决 {}: {}", hook.name(), d.action(), d.reason());
                return d;
            } catch (Exception e) {
                log.warn("StopHook [{}] 异常，忽略: {}", hook.name(), e.getMessage());
            }
        }
        return StopDecision.proceed();
    }

    public int size() {
        return hooks.size();
    }
}
