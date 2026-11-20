package com.miniagent.config.service;

import com.miniagent.config.entity.AgentTaskRun;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * Low-cardinality metrics for durable user task runs.
 *
 * <p>Run IDs, users, sessions, and error text are deliberately not labels. A
 * terminal observation is emitted only after the durable RUNNING -> terminal
 * compare-and-set commits, so retries and stale workers cannot inflate counts.</p>
 */
@Component
public final class TaskRunMetrics {
    public static final String STARTED = "miniagent.task.runs.started";
    public static final String TERMINAL = "miniagent.task.runs.terminal";
    public static final String DURATION = "miniagent.task.run.duration";
    private static final String STATUS_TAG = "status";

    private final Counter started;
    private final Map<AgentTaskRun.Status, Counter> terminal;
    private final Map<AgentTaskRun.Status, Timer> duration;

    public TaskRunMetrics(ObjectProvider<MeterRegistry> registryProvider) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            started = null;
            terminal = Map.of();
            duration = Map.of();
            return;
        }
        started = Counter.builder(STARTED)
                .description("User task runs successfully persisted as RUNNING")
                .register(registry);
        EnumMap<AgentTaskRun.Status, Counter> terminalCounters = new EnumMap<>(AgentTaskRun.Status.class);
        EnumMap<AgentTaskRun.Status, Timer> durationTimers = new EnumMap<>(AgentTaskRun.Status.class);
        for (AgentTaskRun.Status status : AgentTaskRun.Status.values()) {
            if (status == AgentTaskRun.Status.RUNNING) continue;
            String tagValue = status.name().toLowerCase(java.util.Locale.ROOT);
            terminalCounters.put(status, Counter.builder(TERMINAL)
                    .description("User task runs committed to a terminal or yielded state")
                    .tag(STATUS_TAG, tagValue)
                    .register(registry));
            durationTimers.put(status, Timer.builder(DURATION)
                    .description("Elapsed time from task admission to its durable outcome")
                    .tag(STATUS_TAG, tagValue)
                    .register(registry));
        }
        terminal = Map.copyOf(terminalCounters);
        duration = Map.copyOf(durationTimers);
    }

    public void started() {
        if (started != null) started.increment();
    }

    public void terminal(AgentTaskRun.Status status, LocalDateTime startedAt, LocalDateTime finishedAt) {
        if (status == null || status == AgentTaskRun.Status.RUNNING) return;
        Counter counter = terminal.get(status);
        Timer timer = duration.get(status);
        if (counter == null || timer == null) return;
        counter.increment();
        if (startedAt == null || finishedAt == null) return;
        Duration elapsed = Duration.between(startedAt, finishedAt);
        timer.record(elapsed.isNegative() ? Duration.ZERO : elapsed);
    }
}
