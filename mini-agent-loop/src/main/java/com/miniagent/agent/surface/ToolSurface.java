package com.miniagent.agent.surface;

import com.miniagent.agent.task.TaskPlan;
import com.miniagent.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 本轮工具面。
 *
 * <p>只有两种结果：全量（不限制），或一份显式白名单。白名单只有一个来源 ——
 * 提案闸门与子代理派发时指定的工具集。主链路永远是全量：</p>
 *
 * <p>裁剪放在入场时是不可恢复的（工具压根没发给模型，模型连知道它存在的机会都没有），
 * 而放在执行前是可恢复的（拦住只是本轮不可用，换个条件就能放开）。
 * 所以按类别裁剪这一步在这里被删除，收口交给权限门。</p>
 */
@Slf4j
@Component
public class ToolSurface {

    /** {@code null} 名单表示全量（含 MCP），空表表示显式不开工具。 */
    public record Resolved(boolean unrestricted, Set<String> names) {
        public Resolved {
            names = names == null ? Set.of() : Set.copyOf(names);
        }

        public Set<String> allowedToolSet() {
            return unrestricted ? null : names;
        }

        public static Resolved of(Collection<String> names) {
            return new Resolved(false,
                    names == null ? Set.of() : Set.copyOf(names));
        }

        public static Resolved none() {
            return new Resolved(false, Set.of());
        }
    }

    @Autowired
    private ToolRegistry toolRegistry;

    public ToolSurface() {
    }

    ToolSurface(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 显式白名单优先，否则全量。
     *
     * @param plan         本轮的 TaskPlan，只读取 {@code allowedTools}
     * @param policyTools  提案闸门下发的工具名单
     * @param forcePolicy  是否强制使用 {@code policyTools}
     */
    public Resolved resolveTurn(TaskPlan plan, List<String> policyTools, boolean forcePolicy) {
        Set<String> registered = toolRegistry == null
                ? Set.of() : toolRegistry.getToolNames();
        return resolveTurn(plan, policyTools, forcePolicy, registered);
    }

    static Resolved resolveTurn(TaskPlan plan, List<String> policyTools,
                                boolean forcePolicy, Set<String> registered) {
        Set<String> names = registered == null ? Set.of() : registered;
        if (forcePolicy && policyTools != null && !policyTools.isEmpty()) {
            return intersectRegistered(policyTools, names);
        }
        if (plan != null && plan.allowedTools() != null) {
            return intersectRegistered(plan.allowedTools(), names);
        }
        return new Resolved(true, Set.copyOf(names));
    }

    /**
     * 白名单里可能写出未注册的工具名（子代理角色配置是人写的），
     * 这里按注册表过滤，避免把不存在的名字送到工具规格装配。
     */
    static Resolved intersectRegistered(Collection<String> wanted, Set<String> registered) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (wanted == null) {
            return new Resolved(true, Set.copyOf(registered));
        }
        for (String t : wanted) {
            if (t == null || t.isBlank()) {
                continue;
            }
            if (registered.isEmpty() || registered.contains(t)) {
                out.add(t);
            }
        }
        return Resolved.of(out);
    }
}
