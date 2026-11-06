package com.miniagent.memoryservice.evolution;

import com.miniagent.memoryservice.store.JobQueue;
import com.miniagent.memoryservice.store.ProposalState;
import com.miniagent.memoryservice.store.ReportStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 定时把库内状态刷成仪表：作业积压、各健康状态条目数、未终结提案数与运行中实验数。 */
@Component
public class GaugeRefresher {
    private static final String KEY_SEPARATOR = ":";

    private final JobQueue jobs;
    private final ReportStore reports;
    private final MultiGauge jobGauge;
    private final MultiGauge healthGauge;
    private final MultiGauge proposalGauge;
    private final AtomicLong experimentsRunning = new AtomicLong();

    public GaugeRefresher(JobQueue jobs, ReportStore reports, MeterRegistry metrics) {
        this.jobs = jobs;
        this.reports = reports;
        this.jobGauge = MultiGauge.builder("evo_jobs").register(metrics);
        this.healthGauge = MultiGauge.builder("memory_item_health").register(metrics);
        this.proposalGauge = MultiGauge.builder("evo_proposals_open").register(metrics);
        metrics.gauge("evo_experiments_running", experimentsRunning);
    }

    @Scheduled(initialDelayString = "${memory.evolution.metrics.refresh-ms:60000}",
            fixedDelayString = "${memory.evolution.metrics.refresh-ms:60000}")
    public void refresh() {
        jobGauge.register(jobs.depthByTypeAndStatus().entrySet().stream()
                .<MultiGauge.Row<?>>map(e -> {
                    String[] key = e.getKey().split(KEY_SEPARATOR, 2);
                    return MultiGauge.Row.of(Tags.of("type", key[0], "status", key[1]),
                            e.getValue());
                }).toList(), true);
        healthGauge.register(rows("state", reports.healthCounts(null)), true);
        Map<String, Long> open = reports.openProposalCounts();
        proposalGauge.register(rows("state", open), true);
        experimentsRunning.set(open.getOrDefault(ProposalState.EXPERIMENTING.name(), 0L));
    }

    private static List<MultiGauge.Row<?>> rows(String tag, Map<String, Long> counts) {
        return counts.entrySet().stream()
                .<MultiGauge.Row<?>>map(e -> MultiGauge.Row.of(
                        Tags.of(tag, String.valueOf(e.getKey())), e.getValue()))
                .toList();
    }
}
