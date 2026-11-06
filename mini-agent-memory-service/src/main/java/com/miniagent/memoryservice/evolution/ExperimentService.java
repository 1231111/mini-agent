package com.miniagent.memoryservice.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.memory.contract.MemoryApi.Arm;
import com.miniagent.memory.contract.MemoryApi.ProposalView;
import com.miniagent.memory.contract.MemoryApi.ScopeType;
import com.miniagent.memory.contract.ServiceToken.Principal;
import com.miniagent.memoryservice.config.EvolutionProperties;
import com.miniagent.memoryservice.store.AuditStore;
import com.miniagent.memoryservice.store.ItemStore;
import com.miniagent.memoryservice.store.ItemStore.Item;
import com.miniagent.memoryservice.store.ItemStore.Reason;
import com.miniagent.memoryservice.store.ItemStore.Revision;
import com.miniagent.memoryservice.store.JobQueue;
import com.miniagent.memoryservice.store.OutcomeStore;
import com.miniagent.memoryservice.store.OutcomeStore.Counts;
import com.miniagent.memoryservice.store.ProjectAccess;
import com.miniagent.memoryservice.store.ProposalState;
import com.miniagent.memoryservice.store.ProposalStore;
import com.miniagent.memoryservice.store.ProposalStore.Proposal;
import com.miniagent.memoryservice.store.ProposalStore.Trigger;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 在线实验、晋升、监控、回滚与审批（设计文档 §6.3 / §7.4 / §7.5）。
 * 成败按任务计；分臂由 {@link ContextAssembler} 用同一实验盐决定。
 *
 * <p>未实现的护栏：两臂引用率差（需要节点级「是否引用」信号）、单任务 token 与 p95 时延涨幅、
 * 判定器 / 环境版本变化时作废实验。</p>
 */
@Service
public class ExperimentService {
    private static final Logger log = LoggerFactory.getLogger(ExperimentService.class);
    private static final String ACTOR = "evolution";
    /** ponytail: 提案列表只看用户可见的前 500 个条目；条目更多时改成按提案表分页。 */
    private static final int MAX_LISTED_ITEMS = 500;

    private final ItemStore items;
    private final ProposalStore proposals;
    private final JobQueue jobs;
    private final OutcomeStore outcomes;
    private final ProjectAccess projects;
    private final EvidenceCases evidence;
    private final AuditStore audit;
    private final EvolutionPolicy policy;
    private final EvolutionProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry metrics;

    public ExperimentService(ItemStore items, ProposalStore proposals, JobQueue jobs,
                             OutcomeStore outcomes, ProjectAccess projects, EvidenceCases evidence,
                             AuditStore audit, EvolutionPolicy policy, EvolutionProperties props,
                             ObjectMapper json, Clock clock, MeterRegistry metrics) {
        this.items = items;
        this.proposals = proposals;
        this.jobs = jobs;
        this.outcomes = outcomes;
        this.projects = projects;
        this.evidence = evidence;
        this.audit = audit;
        this.policy = policy;
        this.props = props;
        this.json = json;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * 「流量足」：按近 traffic-window-days 天的任务数外推，max-days 天内较小的一臂（按最终流量）
     * 能拿到 min-samples-per-arm 个任务。
     */
    public boolean trafficSufficient(Item item) {
        EvolutionProperties.Experiment e = props.getExperiment();
        Instant since = clock.instant().minus(Duration.ofDays(e.getTrafficWindowDays()));
        double perDay = (double) outcomes.distinctTasks(item.id(), since) / e.getTrafficWindowDays();
        double share = (double) finalBps(item) / Stats.BUCKETS;
        double expectedPerArm = perDay * e.getMaxDays() * Math.min(share, 1 - share);
        return expectedPerArm >= e.getMinSamplesPerArm();
    }

    /** 调用方须在事务内。from 为 EVALUATING（AUTO）或 APPROVAL_PENDING（审批通过）。 */
    public boolean start(Proposal p, Item item, ProposalState from, String reviewer) {
        Instant now = clock.instant();
        int bps = item.scopeType() == ScopeType.USER ? props.getExperiment().getUserScopeBps()
                : props.getExperiment().getProjectRampBps().getFirst();
        if (!proposals.startExperiment(p.id(), from, UUID.randomUUID().toString(), bps, reviewer,
                now)) {
            return false;
        }
        jobs.enqueue(JobQueue.Type.EXPERIMENT_CHECK, "EXP:" + p.id(), p.tenantId(), p.itemId(),
                p.id(), null, p.trigger().priority(), next(now, experimentInterval()), now);
        audit.audit(p.tenantId(), actor(reviewer), "EXPERIMENT_STARTED", "PROPOSAL", p.id(),
                Map.of("trafficBps", bps), now);
        metrics.counter("evo_experiments_started_total").increment();
        return true;
    }

    /** 每小时一次：先护栏，再判定，再爬坡，最后看是否到期。返回下次检查时间，null 表示结束。 */
    @Transactional
    public Instant checkExperiment(JobQueue.Job job) {
        Proposal p = proposals.find(job.proposalId()).orElse(null);
        if (p == null || p.state() != ProposalState.EXPERIMENTING) {
            return null;
        }
        Instant now = clock.instant();
        Item item = items.find(p.itemId()).orElse(null);
        if (item == null || !Objects.equals(item.activeRevisionId(), p.baseRevisionId())) {
            finish(p, ProposalState.SUPERSEDED, "实验期间基线已变化", null, now);
            return null;
        }
        if (!policy.of(p.tenantId()).evolves()) {
            return next(now, experimentInterval());
        }
        if (fingerprintRecurred(p, p.experimentStartedAt())) {
            reject(p, "护栏：候选臂上目标指纹复现", null, now);
            return null;
        }
        Map<String, Counts> arms = outcomes.armStats(p.id(), p.experimentStartedAt());
        Counts base = arms.getOrDefault(Arm.BASELINE.name(), Counts.EMPTY);
        Counts cand = arms.getOrDefault(Arm.CANDIDATE.name(), Counts.EMPTY);
        EvolutionProperties.Experiment e = props.getExperiment();
        if (base.tasks() >= e.getMinSamplesPerArm() && cand.tasks() >= e.getMinSamplesPerArm()) {
            double better = Stats.probabilityBetter(base.successes(), base.tasks(),
                    cand.successes(), cand.tasks(), 0);
            double worse = 1 - Stats.probabilityBetter(base.successes(), base.tasks(),
                    cand.successes(), cand.tasks(), e.getNonInferiorityMargin());
            String decision = decision(base, cand, better, worse);
            if (worse >= e.getRejectProbability()) {
                reject(p, "候选显著劣于对照", decision, now);
                return null;
            }
            if (better >= e.getPromoteProbability()) {
                promote(p, item, ProposalState.EXPERIMENTING, decision, null, true);
                return null;
            }
        }
        if (now.isAfter(p.experimentStartedAt().plus(Duration.ofDays(e.getMaxDays())))) {
            reject(p, "到期不显著，保留基线", decision(base, cand, Double.NaN, Double.NaN), now);
            return null;
        }
        rampIfDue(p, item, now);
        return next(now, experimentInterval());
    }

    /** 晋升：CAS 切激活指针，进入监控期。流量足的保留 control-bps 给旧修订做同期对照。 */
    public boolean promote(Proposal p, Item item, ProposalState from, String decisionJson,
                           String reviewer, boolean withControl) {
        Instant now = clock.instant();
        Item locked = items.lock(item.id()).orElseThrow();
        if (!Objects.equals(locked.activeRevisionId(), p.baseRevisionId())
                || !items.activate(locked, p.candidateRevisionId(), Reason.PROMOTE, p.id(),
                actor(reviewer), policy.period(), now)) {
            finish(p, ProposalState.SUPERSEDED, "晋升时基线已变化", null, now);
            return false;
        }
        int treatment = withControl ? Stats.BUCKETS - props.getMonitor().getControlBps()
                : Stats.BUCKETS;
        Instant monitorUntil = now.plus(Duration.ofDays(props.getMonitor().getDays()));
        proposals.promote(p.id(), from, UUID.randomUUID().toString(), treatment, monitorUntil,
                decisionJson, reviewer, now);
        jobs.cancelForProposal(p.id(), now);
        jobs.enqueue(JobQueue.Type.MONITOR_CHECK, "MON:" + p.id(), p.tenantId(), p.itemId(),
                p.id(), null, p.trigger().priority(), next(now, monitorInterval()), now);
        audit.audit(p.tenantId(), actor(reviewer), "PROPOSAL_PROMOTED", "PROPOSAL", p.id(),
                Map.of("itemId", p.itemId(), "control", withControl), now);
        metrics.counter("evo_promotions_total").increment();
        log.info("提案晋升 proposal={} item={} control={}", p.id(), p.itemId(), withControl);
        return true;
    }

    /** 监控期：目标指纹复现或显著退化即回滚；到期进入 STABLE。冻结期间不自动回滚。 */
    @Transactional
    public Instant checkMonitor(JobQueue.Job job) {
        Proposal p = proposals.find(job.proposalId()).orElse(null);
        if (p == null || p.state() != ProposalState.MONITORING) {
            return null;
        }
        Instant now = clock.instant();
        Item item = items.find(p.itemId()).orElse(null);
        if (item == null || !Objects.equals(item.activeRevisionId(), p.candidateRevisionId())) {
            finish(p, ProposalState.SUPERSEDED, "监控期间激活修订已被改动", null, now);
            return null;
        }
        if (!policy.of(p.tenantId()).evolves()) {
            return next(now, monitorInterval());
        }
        if (fingerprintRecurred(p, p.promotedAt())) {
            rollback(p, "护栏：监控期目标指纹复现", ACTOR);
            return null;
        }
        Map<String, Counts> arms = outcomes.armStats(p.id(), p.promotedAt());
        Counts cand = arms.getOrDefault(Arm.CANDIDATE.name(), Counts.EMPTY);
        String degraded = p.trafficBps() < Stats.BUCKETS
                ? degradedAgainstControl(arms.getOrDefault(Arm.BASELINE.name(), Counts.EMPTY), cand)
                : degradedAgainstHistory(p, cand);
        if (degraded != null) {
            rollback(p, degraded, ACTOR);
            return null;
        }
        if (!now.isBefore(p.monitorUntil())) {
            stabilize(p, item, now);
            return null;
        }
        return next(now, monitorInterval());
    }

    /** 回滚：指针切回基线（CAS），提案 ROLLED_BACK，候选哈希进黑名单；随后检查熔断。 */
    @Transactional
    public boolean rollback(Proposal p, String reason, String actor) {
        Instant now = clock.instant();
        Item locked = items.lock(p.itemId()).orElse(null);
        if (locked != null && Objects.equals(locked.activeRevisionId(), p.candidateRevisionId())
                && !items.activate(locked, p.baseRevisionId(), Reason.ROLLBACK, p.id(), actor,
                policy.period(), now)) {
            return false;
        }
        if (!finish(p, ProposalState.ROLLED_BACK, reason, null, now)) {
            return false;
        }
        metrics.counter("evo_rollbacks_total").increment();
        log.warn("提案回滚 proposal={} item={} reason={}", p.id(), p.itemId(), reason);
        breakerCheck(p.tenantId());
        return true;
    }

    /** 运维批量回滚：since 之后由进化晋升、且仍处于激活状态的修订全部切回晋升前的修订。 */
    @Transactional
    public int rollbackSince(String tenantId, Instant since, String reason, String actor) {
        int rolledBack = 0;
        Instant now = clock.instant();
        for (ItemStore.Activation a : items.promotionsSince(tenantId, since)) {
            Item item = items.lock(a.itemId()).orElse(null);
            Proposal p = a.proposalId() == null ? null : proposals.find(a.proposalId()).orElse(null);
            if (item == null || p == null) {
                continue;
            }
            if (items.activate(item, p.baseRevisionId(), Reason.ADMIN_ROLLBACK, p.id(), actor,
                    policy.period(), now)) {
                finish(p, ProposalState.ROLLED_BACK, reason, null, now);
                rolledBack++;
            }
        }
        audit.audit(tenantId, actor, "ADMIN_ROLLBACK_SINCE", "TENANT", tenantId,
                Map.of("since", since.toString(), "count", rolledBack), now);
        return rolledBack;
    }

    /** 审批通过：先过期、锁定、冻结检查；流量足进实验，不足直接晋升进监控（监控护栏照常）。 */
    @Transactional
    public ProposalView approve(Principal principal, Item item, long proposalId, String note) {
        Proposal p = requirePending(proposalId, item);
        String reviewer = ItemService.actor(principal);
        if (item.evolutionLocked()) {
            throw new BusinessException(ErrorCode.MEMORY_ITEM_EVOLUTION_LOCKED);
        }
        if (policy.of(p.tenantId()).frozen()) {
            throw new BusinessException(ErrorCode.MEMORY_EVOLUTION_FROZEN);
        }
        proposals.review(p.id(), ProposalState.APPROVAL_PENDING, ProposalState.APPROVAL_PENDING,
                reviewer, note, clock.instant());
        boolean moved;
        if (p.trigger() == Trigger.LIFECYCLE) {
            moved = retire(p, ProposalState.APPROVAL_PENDING, reviewer);
        } else if (trafficSufficient(item)) {
            moved = start(p, item, ProposalState.APPROVAL_PENDING, reviewer);
        } else {
            moved = promote(p, item, ProposalState.APPROVAL_PENDING, null, reviewer, false);
        }
        if (!moved) {
            throw new BusinessException(ErrorCode.MEMORY_PROPOSAL_STATE_INVALID);
        }
        return view(proposals.find(p.id()).orElseThrow());
    }

    @Transactional
    public ProposalView reject(Principal principal, Item item, long proposalId, String note) {
        Proposal p = requirePending(proposalId, item);
        if (!proposals.review(p.id(), ProposalState.APPROVAL_PENDING, ProposalState.REJECTED,
                ItemService.actor(principal), note, clock.instant())) {
            throw new BusinessException(ErrorCode.MEMORY_PROPOSAL_STATE_INVALID);
        }
        jobs.cancelForProposal(p.id(), clock.instant());
        return view(proposals.find(p.id()).orElseThrow());
    }

    /** 归档类提案（§6.3）：SHADOW 只记录，ASSISTED 进审批，AUTO 直接归档（可一键恢复）。 */
    @Transactional
    public void decideLifecycle(long proposalId) {
        Proposal p = proposals.find(proposalId).orElse(null);
        if (p == null || p.state() != ProposalState.DETECTED) {
            return;
        }
        Instant now = clock.instant();
        switch (policy.of(p.tenantId()).mode()) {
            case SHADOW -> proposals.finish(p.id(), ProposalState.DETECTED,
                    ProposalState.SHADOW_RECORDED, "SHADOW 模式仅记录归档建议", null, now);
            case ASSISTED -> proposals.toApproval(p.id(), ProposalState.DETECTED, "建议归档",
                    approvalExpiry(now), now);
            default -> retire(p, ProposalState.DETECTED, ACTOR);
        }
    }

    /** 审批超期按拒绝处理。 */
    @Transactional
    public int expireApprovals(int limit) {
        Instant now = clock.instant();
        int expired = 0;
        for (Proposal p : proposals.expiredApprovals(now, limit)) {
            if (finish(p, ProposalState.REJECTED, "审批过期", null, now)) {
                expired++;
            }
        }
        return expired;
    }

    /** 作业多次失败进死信：监控中的保守回滚，其余未终结提案转 FAILED。 */
    @Transactional
    public void onDeadJob(JobQueue.Job job) {
        Proposal p = job.proposalId() == null ? null : proposals.find(job.proposalId()).orElse(null);
        if (p == null || !p.state().open()) {
            return;
        }
        if (p.state() == ProposalState.MONITORING) {
            rollback(p, "监控作业多次失败，保守回滚", ACTOR);
        } else {
            finish(p, ProposalState.FAILED, "作业多次失败：" + job.type(), null, clock.instant());
        }
    }

    /** 窗口内晋升数 ≥ breaker.min-promotions 且回滚率 > breaker.rollback-rate → 租户降为 ASSISTED。 */
    public void breakerCheck(String tenantId) {
        EvolutionProperties.Breaker b = props.getBreaker();
        Instant since = clock.instant().minus(Duration.ofDays(b.getWindowDays()));
        long promotions = proposals.countSince(tenantId, EnumSet.of(ProposalState.MONITORING,
                ProposalState.STABLE, ProposalState.ROLLED_BACK), null, since, true);
        long rollbacks = proposals.countSince(tenantId, EnumSet.of(ProposalState.ROLLED_BACK),
                null, since, true);
        if (promotions >= b.getMinPromotions() && (double) rollbacks / promotions > b.getRollbackRate()
                && policy.downgradeToAssisted(tenantId, "breaker")) {
            audit.audit(tenantId, "breaker", "TENANT_DOWNGRADED", "TENANT", tenantId,
                    Map.of("promotions", promotions, "rollbacks", rollbacks), clock.instant());
            log.warn("熔断：租户 {} 回滚率过高，降为 ASSISTED", tenantId);
        }
    }

    public List<ProposalView> list(Principal principal, ProposalState state, int limit) {
        ItemService.requireUser(principal);
        List<Long> visible = items.visible(principal.tenantId(), principal.userId(),
                        projects.projectsOf(principal.tenantId(), principal.userId()), null, null,
                        MAX_LISTED_ITEMS)
                .stream().map(Item::id).toList();
        return proposals.list(principal.tenantId(), visible,
                        state == null ? EnumSet.allOf(ProposalState.class) : EnumSet.of(state), limit)
                .stream().map(this::view).toList();
    }

    public ProposalView view(Proposal p) {
        Item item = items.find(p.itemId()).orElse(null);
        Revision base = items.revision(p.baseRevisionId()).orElse(null);
        Revision cand = items.revision(p.candidateRevisionId()).orElse(null);
        return new ProposalView(p.id(), p.itemId(), item == null ? null : item.name(),
                p.state().name(), p.stateReason(), p.trigger().name(),
                base == null ? 0 : base.revisionNo(),
                cand == null ? null : cand.revisionNo(), base == null ? null : base.content(),
                cand == null ? null : cand.content(), p.rationale(), p.evalJson(),
                EvolutionPolicy.percent(p.trafficBps()), p.decisionJson(), p.createdAt(),
                p.updatedAt());
    }

    private void stabilize(Proposal p, Item item, Instant now) {
        if (!finish(p, ProposalState.STABLE, "监控期通过", null, now)) {
            return;
        }
        EvolutionProperties.Eval e = props.getEval();
        for (EvidenceCases.Case c : evidence.triggers(p, e.getMaxRegressionCases())) {
            audit.addRegressionCase(item.id(), p.id(), p.fingerprint(), c.userMessage(),
                    c.failureReason(), props.getRegression().getMaxCasesPerItem(), now);
        }
        Instant ranSince = p.experimentStartedAt() != null ? p.experimentStartedAt() : p.promotedAt();
        String env = outcomes.dominantEnv(p.candidateRevisionId(), ranSince);
        if (env != null) {
            items.setValidatedEnv(item.id(), env);
        }
        metrics.counter("evo_stable_total").increment();
    }

    private boolean retire(Proposal p, ProposalState from, String actor) {
        Instant now = clock.instant();
        Item locked = items.lock(p.itemId()).orElse(null);
        if (locked == null || !items.changeStatus(locked, ItemStore.STATUS_ARCHIVED, Reason.ARCHIVE,
                actor(actor), now)) {
            finish(p, ProposalState.SUPERSEDED, "归档时条目已变化", null, now);
            return false;
        }
        return proposals.finish(p.id(), from, ProposalState.RETIRED, "已归档，可在变更列表恢复",
                null, now);
    }

    private void reject(Proposal p, String reason, String decision, Instant now) {
        finish(p, ProposalState.REJECTED, reason, decision, now);
        metrics.counter("evo_experiments_rejected_total").increment();
    }

    private boolean finish(Proposal p, ProposalState to, String reason, String decision,
                           Instant now) {
        boolean done = proposals.finish(p.id(), p.state(), to, reason, decision, now);
        if (done) {
            jobs.cancelForProposal(p.id(), now);
            audit.audit(p.tenantId(), ACTOR, "PROPOSAL_" + to.name(), "PROPOSAL", p.id(),
                    Map.of("reason", reason), now);
        }
        return done;
    }

    private void rampIfDue(Proposal p, Item item, Instant now) {
        List<Integer> ramp = props.getExperiment().getProjectRampBps();
        boolean due = item.scopeType() == ScopeType.PROJECT && p.rampStep() < ramp.size() - 1
                && !now.isBefore(p.stepStartedAt().plus(
                Duration.ofHours(props.getExperiment().getRampMinHours())));
        if (due && proposals.ramp(p.id(), p.rampStep() + 1, ramp.get(p.rampStep() + 1), now)) {
            audit.audit(p.tenantId(), ACTOR, "EXPERIMENT_RAMPED", "PROPOSAL", p.id(),
                    Map.of("trafficBps", ramp.get(p.rampStep() + 1)), now);
        }
    }

    private boolean fingerprintRecurred(Proposal p, Instant since) {
        return p.fingerprint() != null && outcomes.armRecurrences(p.id(), Arm.CANDIDATE.name(),
                p.fingerprint(), since) >= props.getRecurrence().getMinCount();
    }

    private String degradedAgainstControl(Counts base, Counts cand) {
        EvolutionProperties.Experiment e = props.getExperiment();
        if (base.tasks() < e.getMinSamplesPerArm() || cand.tasks() < e.getMinSamplesPerArm()) {
            return null;
        }
        double worse = 1 - Stats.probabilityBetter(base.successes(), base.tasks(),
                cand.successes(), cand.tasks(), e.getNonInferiorityMargin());
        return worse >= e.getRejectProbability() ? "护栏：监控期显著劣于同期对照" : null;
    }

    private String degradedAgainstHistory(Proposal p, Counts cand) {
        if (cand.tasks() < props.getMonitor().getMinTasksForHistoryComparison()) {
            return null;
        }
        Instant before = p.promotedAt().minus(
                Duration.ofDays(props.getExperiment().getTrafficWindowDays()));
        Counts history = outcomes.revisionStats(p.baseRevisionId(), before, p.promotedAt());
        double worse = 1 - Stats.probabilityBetter(history.successes(), history.tasks(),
                cand.successes(), cand.tasks(), props.getHealth().getMargin());
        return worse >= props.getHealth().getDegradeProbability()
                ? "护栏：监控期较晋升前显著退化" : null;
    }

    private Proposal requirePending(long proposalId, Item item) {
        Proposal p = proposals.find(proposalId).orElse(null);
        if (p == null || p.itemId() != item.id() || p.state() != ProposalState.APPROVAL_PENDING) {
            throw new BusinessException(ErrorCode.MEMORY_PROPOSAL_STATE_INVALID);
        }
        Instant now = clock.instant();
        if (p.expiresAt() != null && now.isAfter(p.expiresAt())) {
            finish(p, ProposalState.REJECTED, "审批过期", null, now);
            throw new BusinessException(ErrorCode.MEMORY_PROPOSAL_EXPIRED);
        }
        return p;
    }

    private int finalBps(Item item) {
        return item.scopeType() == ScopeType.USER ? props.getExperiment().getUserScopeBps()
                : props.getExperiment().getProjectRampBps().getLast();
    }

    Instant approvalExpiry(Instant now) {
        return now.plus(Duration.ofDays(props.getApproval().getExpireDays()));
    }

    private Duration experimentInterval() {
        return Duration.ofMinutes(props.getExperiment().getCheckIntervalMinutes());
    }

    private Duration monitorInterval() {
        return Duration.ofMinutes(props.getMonitor().getCheckIntervalMinutes());
    }

    private static Instant next(Instant now, Duration interval) {
        return now.plus(interval);
    }

    private static String actor(String reviewer) {
        return reviewer == null ? ACTOR : reviewer;
    }

    private String decision(Counts base, Counts cand, double better, double worse) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("baselineTasks", base.tasks());
        d.put("baselineSuccesses", base.successes());
        d.put("candidateTasks", cand.tasks());
        d.put("candidateSuccesses", cand.successes());
        if (!Double.isNaN(better)) {
            d.put("pCandidateBetter", better);
            d.put("pCandidateWorse", worse);
        }
        try {
            return json.writeValueAsString(d);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
