package com.miniagent.memoryservice.evolution;

import com.miniagent.memoryservice.config.EvolutionProperties;
import com.miniagent.memoryservice.store.AuditStore;
import com.miniagent.memoryservice.store.ItemStore;
import com.miniagent.memoryservice.store.ItemStore.Item;
import com.miniagent.memoryservice.store.JobQueue;
import com.miniagent.memoryservice.store.OutcomeStore;
import com.miniagent.memoryservice.store.OutcomeStore.Counts;
import com.miniagent.memoryservice.store.PolicyStore;
import com.miniagent.memoryservice.store.ProposalState;
import com.miniagent.memoryservice.store.ProposalStore;
import com.miniagent.memoryservice.store.ProposalStore.Proposal;
import com.miniagent.memoryservice.store.ProposalStore.Trigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 中环（设计文档 §5.3）：每天的健康分、审批过期、离线-在线一致率、熔断与数据保留；
 * 每周的优化扫描与归档。两个周期各是一行自我续期的 SWEEP 作业，多副本只会有一个在跑。
 *
 * <p>ponytail: 逐条目全表分页扫描；条目上十万后改成按租户分片的作业。</p>
 */
@Service
public class Sweeper {
    public static final String DAILY = "SWEEP:DAILY";
    public static final String WEEKLY = "SWEEP:WEEKLY";
    private static final Logger log = LoggerFactory.getLogger(Sweeper.class);
    private static final Duration WEEK = Duration.ofDays(7);
    private static final int PAGE = 200;

    private final ItemStore items;
    private final OutcomeStore outcomes;
    private final ProposalStore proposals;
    private final PolicyStore policies;
    private final JobQueue jobs;
    private final TriggerDetector detector;
    private final ExperimentService experiments;
    private final EvolutionPolicy policy;
    private final AuditStore audit;
    private final EvolutionProperties props;
    private final Clock clock;

    public Sweeper(ItemStore items, OutcomeStore outcomes, ProposalStore proposals,
                   PolicyStore policies, JobQueue jobs, TriggerDetector detector,
                   ExperimentService experiments, EvolutionPolicy policy, AuditStore audit,
                   EvolutionProperties props, Clock clock) {
        this.items = items;
        this.outcomes = outcomes;
        this.proposals = proposals;
        this.policies = policies;
        this.jobs = jobs;
        this.detector = detector;
        this.experiments = experiments;
        this.policy = policy;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
    }

    /** 返回下次运行时间。 */
    public Instant run(JobQueue.Job job) {
        Instant now = clock.instant();
        if (WEEKLY.equals(job.openKey())) {
            weekly();
            return now.plus(WEEK);
        }
        daily();
        return now.plus(Duration.ofHours(props.getJobs().getSweepIntervalHours()));
    }

    public void daily() {
        while (experiments.expireApprovals(PAGE) == PAGE) {
            log.debug("继续处理过期审批");
        }
        forEachActive(this::health);
        for (String tenant : policies.tenants()) {
            agreementCheck(tenant);
            experiments.breakerCheck(tenant);
        }
        Instant now = clock.instant();
        int purged = outcomes.purge(now.minus(Duration.ofDays(props.getRetention().getOutcomeDays())));
        int jobsPurged = jobs.purgeDone(now.minus(Duration.ofDays(
                props.getRetention().getDoneJobDays())));
        log.info("每日扫描完成 purgedOutcomes={} purgedJobs={}", purged, jobsPurged);
    }

    public void weekly() {
        for (String tenant : policies.tenants()) {
            optimize(tenant);
        }
        forEachActive(this::lifecycle);
    }

    /**
     * 健康分：最近 recent-days 与之前 baseline-days 的任务成功率比较。
     * P(最近 < 之前 − margin) ≥ degrade-probability → DEGRADED 并开 HEALTH 提案；任一窗口任务数不足 → UNKNOWN。
     */
    void health(Item item) {
        EvolutionProperties.Health h = props.getHealth();
        Instant now = clock.instant();
        Instant recentFrom = now.minus(Duration.ofDays(h.getRecentDays()));
        Counts recent = outcomes.revisionStats(item.activeRevisionId(), recentFrom, now);
        Counts before = outcomes.revisionStats(item.activeRevisionId(),
                recentFrom.minus(Duration.ofDays(h.getBaselineDays())), recentFrom);
        String health;
        if (recent.tasks() < h.getMinRecentApplications()
                || before.tasks() < h.getMinRecentApplications()) {
            health = ItemStore.HEALTH_UNKNOWN;
        } else {
            double worse = 1 - Stats.probabilityBetter(before.successes(), before.tasks(),
                    recent.successes(), recent.tasks(), h.getMargin());
            health = worse >= h.getDegradeProbability() ? ItemStore.HEALTH_DEGRADED
                    : ItemStore.HEALTH_HEALTHY;
        }
        if (!health.equals(item.health())) {
            items.setHealth(item.id(), health);
        }
        if (ItemStore.HEALTH_DEGRADED.equals(health)) {
            detector.open(item, Trigger.HEALTH, null, Map.of(
                    "recentTasks", recent.tasks(), "recentSuccesses", recent.successes(),
                    "beforeTasks", before.tasks(), "beforeSuccesses", before.successes()));
        }
    }

    /** 最近 agreement-window 个做过在线实验的提案里「离线通过且线上胜出」的比例过低 → 降为 ASSISTED。 */
    void agreementCheck(String tenant) {
        EvolutionProperties.Eval e = props.getEval();
        List<Proposal> decided = proposals.recentOnlineDecided(tenant, e.getAgreementWindow());
        if (decided.size() < e.getAgreementWindow()) {
            return;
        }
        long won = decided.stream().filter(p -> p.promotedAt() != null).count();
        double agreement = (double) won / decided.size();
        if (agreement < e.getMinOfflineOnlineAgreement()
                && policy.downgradeToAssisted(tenant, "agreement")) {
            audit.audit(tenant, "agreement", "TENANT_DOWNGRADED", "TENANT", tenant,
                    Map.of("agreement", agreement), clock.instant());
        }
    }

    /** 流量足且近期成功率低于目标的条目，按「任务数 ×（目标 − 当前）」排序，每租户每周最多 N 个。 */
    void optimize(String tenant) {
        EvolutionProperties.Optimize o = props.getOptimize();
        Instant now = clock.instant();
        long used = proposals.countSince(tenant, EnumSet.allOf(ProposalState.class),
                Trigger.OPTIMIZATION, now.minus(WEEK), false);
        long remaining = o.getMaxPerTenantPerWeek() - used;
        if (remaining <= 0) {
            return;
        }
        Instant since = now.minus(Duration.ofDays(props.getExperiment().getTrafficWindowDays()));
        record Scored(Item item, Counts stats, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        forEachActive(item -> {
            if (!item.tenantId().equals(tenant) || !experiments.trafficSufficient(item)) {
                return;
            }
            Counts stats = outcomes.revisionStats(item.activeRevisionId(), since, now);
            if (stats.tasks() > 0 && stats.successRate() < o.getTargetSuccessRate()) {
                scored.add(new Scored(item, stats,
                        stats.tasks() * (o.getTargetSuccessRate() - stats.successRate())));
            }
        });
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        for (Scored s : scored) {
            if (remaining <= 0) {
                return;
            }
            if (detector.open(s.item(), Trigger.OPTIMIZATION, null, Map.of(
                    "tasks", s.stats().tasks(), "successRate", s.stats().successRate())).isPresent()) {
                remaining--;
            }
        }
    }

    /** 闲置 idle-days 天 → 归档提案；低成功率先给一次修复机会，修复被拒或过期后仍低 → 归档提案。 */
    void lifecycle(Item item) {
        EvolutionProperties.Lifecycle l = props.getLifecycle();
        Instant now = clock.instant();
        Instant idleSince = now.minus(Duration.ofDays(l.getIdleDays()));
        Instant lastUsed = item.lastUsedAt() == null ? item.createdAt() : item.lastUsedAt();
        if (lastUsed.isBefore(idleSince)) {
            detector.open(item, Trigger.LIFECYCLE, null, Map.of("reason", "idle"));
            return;
        }
        Counts stats = outcomes.revisionStats(item.activeRevisionId(),
                now.minus(Duration.ofDays(props.getExperiment().getTrafficWindowDays())), now);
        if (stats.tasks() < l.getArchiveMinApplications()
                || stats.successRate() > l.getArchiveMaxSuccessRate()) {
            return;
        }
        boolean fixAttemptFailed = proposals.inCooldown(item.id(), null, idleSince);
        detector.open(item, fixAttemptFailed ? Trigger.LIFECYCLE : Trigger.OPTIMIZATION, null,
                Map.of("reason", "low-success", "successRate", stats.successRate()));
    }

    private void forEachActive(Consumer<Item> action) {
        Long after = null;
        while (true) {
            List<Item> page = items.byStatus(ItemStore.STATUS_ACTIVE, after, PAGE);
            for (Item item : page) {
                try {
                    action.accept(item);
                } catch (RuntimeException e) {
                    log.warn("扫描条目失败 item={}", item.id(), e);
                }
            }
            if (page.size() < PAGE) {
                return;
            }
            after = page.getLast().id();
        }
    }
}
