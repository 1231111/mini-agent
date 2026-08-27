package com.miniagent.agent.planner;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
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
    private final Map<String, Counter> counters;

    public PlannerMetrics() {
        this.counters = Map.of();
    }

    @Autowired
    public PlannerMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            this.counters = Map.of();
            return;
        }
        this.counters = Map.ofEntries(
                Map.entry("proposals", registry.counter("miniagent.planner.proposals")),
                Map.entry("nodesSuccess", registry.counter("miniagent.planner.nodes.success")),
                Map.entry("nodesFailed", registry.counter("miniagent.planner.nodes.failed")),
                Map.entry("recoveries", registry.counter("miniagent.planner.recoveries")),
                Map.entry("casConflicts", registry.counter("miniagent.planner.cas.conflicts")),
                Map.entry("evalRejects", registry.counter("miniagent.planner.eval.rejects")),
                Map.entry("gateDenies", registry.counter("miniagent.planner.gate.denies")),
                Map.entry("graphsCompleted", registry.counter("miniagent.planner.graphs.completed")),
                Map.entry("outerTimeouts", registry.counter("miniagent.planner.outer.timeouts"))
        );
    }

    public void proposal() { increment("proposals", proposals); }
    public void nodeSuccess() { increment("nodesSuccess", nodesSuccess); }
    public void nodeFailed() { increment("nodesFailed", nodesFailed); }
    public void recovery() { increment("recoveries", recoveries); }
    public void casConflict() { increment("casConflicts", casConflicts); }
    public void evalReject() { increment("evalRejects", evalRejects); }
    public void gateDeny() { increment("gateDenies", gateDenies); }
    public void graphCompleted() { increment("graphsCompleted", graphsCompleted); }
    public void outerTimeout() { increment("outerTimeouts", outerTimeouts); }

    private void increment(String name, AtomicLong local) {
        local.incrementAndGet();
        Counter counter = counters.get(name);
        if (counter != null) {
            counter.increment();
        }
    }

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
