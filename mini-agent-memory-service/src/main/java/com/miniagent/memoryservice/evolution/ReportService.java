package com.miniagent.memoryservice.evolution;

import com.miniagent.memoryservice.store.AuditStore;
import com.miniagent.memoryservice.store.ItemStore;
import com.miniagent.memoryservice.store.OutcomeStore;
import com.miniagent.memoryservice.store.ProposalStore;
import com.miniagent.memoryservice.store.ReportStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期 SLO（设计文档第 13 章）与留存对照报表。比例在分母为 0 时给 null，不伪造 0% / 100%。
 * 留存对照只比任务成功率；文档里的单任务 token 对比未实现（结果事件不带 token）。
 */
@Service
public class ReportService {
    /** 文档：复发率按季度统计；自动晋升回滚率按 30 天统计。 */
    private static final Duration QUARTER = Duration.ofDays(90);
    private static final Duration MONTH = Duration.ofDays(30);
    private static final double Z_95 = 1.96;
    private static final double SECONDS_PER_DAY = 86_400d;

    private final ReportStore reports;
    private final OutcomeStore outcomes;
    private final ProposalStore proposals;
    private final AuditStore audit;
    private final EvolutionPolicy policy;
    private final Clock clock;

    public ReportService(ReportStore reports, OutcomeStore outcomes, ProposalStore proposals,
                         AuditStore audit, EvolutionPolicy policy, Clock clock) {
        this.reports = reports;
        this.outcomes = outcomes;
        this.proposals = proposals;
        this.audit = audit;
        this.policy = policy;
        this.clock = clock;
    }

    public Map<String, Object> slo(String tenantId) {
        Instant now = clock.instant();
        Map<String, Object> slo = new LinkedHashMap<>();
        slo.put("recurrenceRate", ratio(reports.recurredFingerprints(tenantId, now.minus(QUARTER)),
                reports.fixedFingerprints(tenantId)));
        slo.put("medianDaysToFix", medianDays(reports.autoFixSpans(tenantId, now.minus(QUARTER))));
        Map<String, Long> health = reports.healthCounts(tenantId);
        long healthy = health.getOrDefault(ItemStore.HEALTH_HEALTHY, 0L);
        slo.put("healthyShare", ratio(healthy,
                healthy + health.getOrDefault(ItemStore.HEALTH_DEGRADED, 0L)));
        long[] auto = reports.promotions(tenantId, now.minus(MONTH), true);
        slo.put("autoPromotionRollbackRate", ratio(auto[1], auto[0]));
        long[] all = reports.promotions(tenantId, now.minus(QUARTER), false);
        slo.put("userRevertRate", ratio(reports.userReverts(tenantId, now.minus(QUARTER),
                ItemService.USER_REVERT), all[0]));
        long[] cost = reports.costPerStable(tenantId, now.minus(QUARTER));
        slo.put("tokensPerStablePromotion", cost[1] == 0 ? null : cost[0] / cost[1]);
        long[] review = audit.reviewAgreement(now.minus(QUARTER));
        slo.put("humanReviewAgreement", ratio(review[0], review[1]));
        slo.put("holdout", holdout(tenantId));
        slo.put("byGenerator", proposals.summary(tenantId, now.minus(QUARTER)));
        return slo;
    }

    /** 本留存周期内：非留存任务成功率 − 留存任务成功率，及其 95% 区间（正态近似）。 */
    public Map<String, Object> holdout(String tenantId) {
        Map<Boolean, OutcomeStore.Counts> stats = outcomes.holdoutStats(tenantId, policy.periodStart());
        OutcomeStore.Counts held = stats.getOrDefault(true, OutcomeStore.Counts.EMPTY);
        OutcomeStore.Counts treated = stats.getOrDefault(false, OutcomeStore.Counts.EMPTY);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("periodStart", policy.periodStart().toString());
        report.put("holdoutBps", policy.of(tenantId).holdoutBps());
        report.put("holdoutTasks", held.tasks());
        report.put("treatedTasks", treated.tasks());
        if (held.tasks() == 0 || treated.tasks() == 0) {
            report.put("netEffect", null);
            return report;
        }
        double pH = held.successRate();
        double pT = treated.successRate();
        double se = Math.sqrt(pH * (1 - pH) / held.tasks() + pT * (1 - pT) / treated.tasks());
        report.put("netEffect", pT - pH);
        report.put("ci95Low", pT - pH - Z_95 * se);
        report.put("ci95High", pT - pH + Z_95 * se);
        return report;
    }

    static Double ratio(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }

    static Double medianDays(List<ReportStore.Span> spans) {
        if (spans.isEmpty()) {
            return null;
        }
        double[] days = spans.stream()
                .mapToDouble(s -> Duration.between(s.start(), s.end()).toSeconds() / SECONDS_PER_DAY)
                .sorted().toArray();
        int mid = days.length / 2;
        return days.length % 2 == 1 ? days[mid] : (days[mid - 1] + days[mid]) / 2;
    }
}
