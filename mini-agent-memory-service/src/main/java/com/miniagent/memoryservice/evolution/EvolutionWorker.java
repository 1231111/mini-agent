package com.miniagent.memoryservice.evolution;

import com.miniagent.memoryservice.config.EvolutionProperties;
import com.miniagent.memoryservice.config.MemoryServiceProperties;
import com.miniagent.memoryservice.store.JobQueue;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * worker 角色的作业循环。领取用 SKIP LOCKED，多副本并发互不阻塞；处理器返回下次运行时间
 * （周期性作业复用同一行续期）或 null（完成）。异常按指数退避重试，超过上限进死信并收尾提案。
 * EVALUATE / REVALIDATE 不在这里跑，由同进程的 EvalRunner 领取。
 */
@Component
public class EvolutionWorker {
    private static final Logger log = LoggerFactory.getLogger(EvolutionWorker.class);
    private static final List<JobQueue.Type> TYPES = List.of(JobQueue.Type.DETECT,
            JobQueue.Type.GENERATE, JobQueue.Type.EXPERIMENT_CHECK, JobQueue.Type.MONITOR_CHECK,
            JobQueue.Type.SWEEP);
    private static final Duration ENSURE_SWEEPS_EVERY = Duration.ofMinutes(10);

    private final JobQueue jobs;
    private final TriggerDetector detector;
    private final CandidateGenerator generator;
    private final ExperimentService experiments;
    private final Sweeper sweeper;
    private final MemoryServiceProperties service;
    private final EvolutionProperties props;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final String workerId = ManagementFactory.getRuntimeMXBean().getName();
    private Instant sweepsEnsuredAt = Instant.MIN;

    public EvolutionWorker(JobQueue jobs, TriggerDetector detector, CandidateGenerator generator,
                           ExperimentService experiments, Sweeper sweeper,
                           MemoryServiceProperties service, EvolutionProperties props, Clock clock,
                           MeterRegistry metrics) {
        this.jobs = jobs;
        this.detector = detector;
        this.generator = generator;
        this.experiments = experiments;
        this.sweeper = sweeper;
        this.service = service;
        this.props = props;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Scheduled(initialDelayString = "${memory.evolution.jobs.initial-delay-ms:10000}",
            fixedDelayString = "${memory.evolution.jobs.poll-ms:2000}")
    public void poll() {
        if (service.runsWorker()) {
            runOnce();
        }
    }

    /** 跑一轮：每种作业最多领 batch-size 个。返回处理的作业数（测试用）。 */
    public int runOnce() {
        Instant now = clock.instant();
        if (now.isAfter(sweepsEnsuredAt.plus(ENSURE_SWEEPS_EVERY))) {
            jobs.enqueue(JobQueue.Type.SWEEP, Sweeper.DAILY, null, null, null, null, now, now);
            jobs.enqueue(JobQueue.Type.SWEEP, Sweeper.WEEKLY, null, null, null, null, now, now);
            sweepsEnsuredAt = now;
        }
        EvolutionProperties.Jobs cfg = props.getJobs();
        int processed = 0;
        for (JobQueue.Type type : TYPES) {
            JobQueue.Leased leased = jobs.lease(type, workerId, cfg.getBatchSize(),
                    Duration.ofSeconds(cfg.getLeaseSeconds()), cfg.getMaxAttempts(), now);
            leased.dead().forEach(this::onDead);
            for (JobQueue.Job job : leased.jobs()) {
                run(job);
                processed++;
            }
        }
        return processed;
    }

    private void run(JobQueue.Job job) {
        try {
            Instant next = switch (job.type()) {
                case DETECT -> {
                    detector.detect(job.itemId());
                    yield null;
                }
                case GENERATE -> generator.generate(job);
                case EXPERIMENT_CHECK -> experiments.checkExperiment(job);
                case MONITOR_CHECK -> experiments.checkMonitor(job);
                case SWEEP -> sweeper.run(job);
                case EVALUATE, REVALIDATE -> throw new IllegalStateException("由评估 worker 处理");
            };
            Instant now = clock.instant();
            if (next == null) {
                jobs.complete(job, now);
            } else {
                jobs.reschedule(job, next, now);
            }
        } catch (RuntimeException e) {
            log.warn("作业失败 job={} type={} attempt={}", job.id(), job.type(), job.attempts(), e);
            if (jobs.fail(job, e.toString(), props.getJobs().getMaxAttempts(), clock.instant())) {
                onDead(job);
            }
        }
    }

    private void onDead(JobQueue.Job job) {
        metrics.counter("evo_job_dead_total", "type", job.type().name()).increment();
        log.error("作业进入死信 job={} type={} proposal={}", job.id(), job.type(), job.proposalId());
        experiments.onDeadJob(job);
    }
}
