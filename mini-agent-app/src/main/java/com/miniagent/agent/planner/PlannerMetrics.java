package com.miniagent.agent.planner;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;
import java.util.LinkedHashMap;
import java.util.Map;

/** Planner 灰度指标（进程内计数，多副本各自累计）。 */
@Component
public class PlannerMetrics {

    private final AtomicLong proposals = new AtomicLong();
    private final AtomicLong nodesSuccess = new AtomicLong();
    private final AtomicLong nodesFailed = new AtomicLong();
    private final AtomicLong recoveries = new AtomicLong();
    private final AtomicLong casConflicts = new AtomicLong();
    private final AtomicLong evalRejects = new AtomicLong();
    private final AtomicLong gateDenies = new AtomicLong();
    private final AtomicLong graphsCompleted = new AtomicLong();
    private final AtomicLong outerTimeouts = new AtomicLong();

    public void proposal() { proposals.incrementAndGet(); }
    public void nodeSuccess() { nodesSuccess.incrementAndGet(); }
    public void nodeFailed() { nodesFailed.incrementAndGet(); }
    public void recovery() { recoveries.incrementAndGet(); }
    public void casConflict() { casConflicts.incrementAndGet(); }
    public void evalReject() { evalRejects.incrementAndGet(); }
    public void gateDeny() { gateDenies.incrementAndGet(); }
    public void graphCompleted() { graphsCompleted.incrementAndGet(); }
    public void outerTimeout() { outerTimeouts.incrementAndGet(); }

    public Map<String, Long> snapshot() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("proposals", proposals.get());
        m.put("nodesSuccess", nodesSuccess.get());
        m.put("nodesFailed", nodesFailed.get());
        m.put("recoveries", recoveries.get());
        m.put("casConflicts", casConflicts.get());
        m.put("evalRejects", evalRejects.get());
        m.put("gateDenies", gateDenies.get());
        m.put("graphsCompleted", graphsCompleted.get());
        m.put("outerTimeouts", outerTimeouts.get());
        return m;
    }
}
