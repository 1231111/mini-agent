package com.miniagent.agent.planner;

import com.miniagent.agent.intent.TaskPlan;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * TaskGraph 结构验收：无环、id 唯一、依赖存在、inputs 由前置 outputs 产出。
 */
@Component
public class PlanValidator {

    public boolean accept(TaskGraph g, TaskPlan plan) {
        if (g == null || g.isEmpty()) {
            return false;
        }
        if (g.hasCycle()) {
            return false;
        }
        Set<String> ids = new HashSet<>();
        for (TaskNode n : g.nodes()) {
            if (StringUtils.isBlank(n.id()) || StringUtils.isBlank(n.name())) return false;
            if (!ids.add(n.id())) return false;
            if (!n.doneWhen().valid()) return false;
            for (String d : n.dependsOn())
                if (g.byId(d) == null) return false;
            if (!inputsProducedByDeps(g, n)) return false;
        }
        return true;
    }

    static boolean inputsProducedByDeps(TaskGraph g, TaskNode n) {
        for (String in : n.inputs()) {
            boolean found = false;
            for (String d : n.dependsOn()) {
                TaskNode dep = g.byId(d);
                if (dep != null && dep.outputs().contains(in)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }
}
