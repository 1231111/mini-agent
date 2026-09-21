package com.miniagent.agent.planner;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Scheduler：只选 READY，按 priority 出提案。
 * 参数齐的写/读锁定主工具；其余只带 capability。
 */
@Component
public class GraphScheduler {

    private final ReadyTaskSelector readySelector = new ReadyTaskSelector();
    private final PlannerProperties properties;

    public GraphScheduler() {
        this(new PlannerProperties());
    }

    @Autowired
    public GraphScheduler(PlannerProperties properties) {
        this.properties = properties == null ? new PlannerProperties() : properties;
    }

    public List<TaskNode> select(TaskGraph graph) {
        return readySelector.select(graph);
    }

    public ActionProposal propose(StateSnapshot snap, List<TaskNode> ready, int batchSize) {
        int n = Math.max(1, batchSize);
        List<ActionSpec> actions = new ArrayList<>();
        int taken = 0;
        String session = snap == null || snap.sessionId() == null ? "" : snap.sessionId();
        int timeout = properties.getActionTimeoutSeconds();
        for (TaskNode node : ready) {
            if (taken >= n) {
                break;
            }
            String cap = node.capability() == null ? "" : node.capability();
            String actionId = "act_" + UUID.randomUUID().toString().substring(0, 8);
            String concKey = session + ":" + node.id();
            ActionBinder.Bound bound = ActionBinder.bind(node, snap.graph());
            actions.add(new ActionSpec(
                    actionId,
                    node.id(),
                    bound.tool(), cap, bound.arguments(), node.doneWhen(),
                    node.compensation(),
                    "idem-" + snap.planVersion() + "-" + node.id(), timeout,
                    ActionRetryPolicy.forCapability(cap), concKey));
            taken++;
        }
        return new ActionProposal(
                "prop_" + UUID.randomUUID().toString().substring(0, 8),
                snap.version(),
                snap.planVersion(),
                snap.executionId(),
                actions);
    }
}
