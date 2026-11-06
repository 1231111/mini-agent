package com.miniagent.memoryservice.evolution;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.memory.contract.MemoryApi.CaseKind;
import com.miniagent.memory.contract.MemoryApi.CaseVerdict;
import com.miniagent.memory.contract.MemoryApi.EnvironmentChange;
import com.miniagent.memory.contract.MemoryApi.EvalCase;
import com.miniagent.memory.contract.MemoryApi.EvalJob;
import com.miniagent.memory.contract.MemoryApi.EvalMode;
import com.miniagent.memory.contract.MemoryApi.EvalResult;
import com.miniagent.memory.contract.MemoryApi.Verdict;
import com.miniagent.memoryservice.config.EvolutionProperties;
import com.miniagent.memoryservice.store.AuditStore;
import com.miniagent.memoryservice.store.ItemStore;
import com.miniagent.memoryservice.store.ItemStore.Item;
import com.miniagent.memoryservice.store.ItemStore.Revision;
import com.miniagent.memoryservice.store.JobQueue;
import com.miniagent.memoryservice.store.OutcomeStore;
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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 评估作业协议（设计文档 §7.3 / §7.6）。评估 worker 经 HTTP 领取 EVALUATE / REVALIDATE，
 * 回放规划后回写逐案判定；本类按通过线决定提案去向。
 * 领取时把实际下发的案例存进作业 payload：重试回放同一批案例，回写只认下发过的案例 id。
 */
@Service
public class EvalCoordinator {
    private static final Logger log = LoggerFactory.getLogger(EvalCoordinator.class);
    private static final List<JobQueue.Type> EVAL_TYPES =
            List.of(JobQueue.Type.EVALUATE, JobQueue.Type.REVALIDATE);

    /** 作业 payload：重验带环境版本；cases 在首次领取时填入。 */
    record Payload(String envVersion, List<EvalCase> cases) {
    }

    /** 逐类计数：WIN / LOSS / TIE / ERROR。 */
    record Tally(Map<CaseKind, Map<Verdict, Integer>> counts, int baselineCompileFailures,
                 int candidateCompileFailures) {
        int count(CaseKind kind, Verdict verdict) {
            return counts.getOrDefault(kind, Map.of()).getOrDefault(verdict, 0);
        }

        int total(CaseKind kind) {
            return counts.getOrDefault(kind, Map.of()).values().stream()
                    .mapToInt(Integer::intValue).sum();
        }

        int judged(CaseKind kind) {
            return total(kind) - count(kind, Verdict.ERROR);
        }

        double rate(CaseKind kind, Verdict verdict) {
            int judged = judged(kind);
            return judged == 0 ? 0 : (double) count(kind, verdict) / judged;
        }

        int all() {
            return Stream.of(CaseKind.values()).mapToInt(this::total).sum();
        }

        int errors() {
            return Stream.of(CaseKind.values()).mapToInt(k -> count(k, Verdict.ERROR)).sum();
        }
    }

    private final ItemStore items;
    private final ProposalStore proposals;
    private final JobQueue jobs;
    private final OutcomeStore outcomes;
    private final EvidenceCases evidence;
    private final ExperimentService experiments;
    private final TriggerDetector detector;
    private final EvolutionPolicy policy;
    private final AuditStore audit;
    private final EvolutionProperties props;
    private final ObjectMapper json;
    private final Clock clock;
    private final MeterRegistry metrics;

    public EvalCoordinator(ItemStore items, ProposalStore proposals, JobQueue jobs,
                           OutcomeStore outcomes, EvidenceCases evidence,
                           ExperimentService experiments, TriggerDetector detector,
                           EvolutionPolicy policy, AuditStore audit, EvolutionProperties props,
                           ObjectMapper json, Clock clock, MeterRegistry metrics) {
        this.items = items;
        this.proposals = proposals;
        this.jobs = jobs;
        this.outcomes = outcomes;
        this.evidence = evidence;
        this.experiments = experiments;
        this.detector = detector;
        this.policy = policy;
        this.audit = audit;
        this.props = props;
        this.json = json;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** 领取一个评估作业；没有可做的返回空。EVALUATE 优先于 REVALIDATE（文档：重验优先级最低）。 */
    @Transactional
    public Optional<EvalJob> lease(String workerId) {
        EvolutionProperties.Jobs cfg = props.getJobs();
        Instant now = clock.instant();
        for (JobQueue.Type type : EVAL_TYPES) {
            JobQueue.Leased leased = jobs.lease(type, workerId, 1,
                    Duration.ofSeconds(props.getEval().getLeaseSeconds()), cfg.getMaxAttempts(),
                    now, job -> eligible(job, now));
            leased.dead().forEach(experiments::onDeadJob);
            for (JobQueue.Job job : leased.jobs()) {
                Optional<EvalJob> built = type == JobQueue.Type.EVALUATE ? pairwise(job, now)
                        : revalidation(job, now);
                if (built.isPresent()) {
                    return built;
                }
            }
        }
        return Optional.empty();
    }

    @Transactional
    public void complete(long jobId, EvalResult result) {
        JobQueue.Job job = jobs.find(jobId).orElse(null);
        if (job == null || result == null || !JobQueue.RUNNING.equals(job.status())
                || !Objects.equals(job.leaseToken(), result.leaseToken())) {
            throw new BusinessException(ErrorCode.MEMORY_PROPOSAL_STATE_INVALID, "评估租约已失效");
        }
        Payload payload = read(job.payloadJson());
        if (payload == null || payload.cases() == null) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID, "作业没有下发过案例");
        }
        if (job.tenantId() != null) {
            policy.charge(job.tenantId(), EvolutionPolicy.PURPOSE_EVAL,
                    Math.max(0, result.tokensUsed()));
        }
        Tally tally = tally(payload.cases(), result.verdicts());
        if (job.type() == JobQueue.Type.REVALIDATE) {
            completeRevalidation(job, payload, tally, result);
        } else {
            completePairwise(job, tally, result);
        }
        jobs.complete(job, clock.instant());
    }

    /** 发布流水线登记新环境：按近期任务数取前 max-items-per-change 个条目排重验。 */
    @Transactional
    public int registerEnvironment(EnvironmentChange change, String actor) {
        if (change == null || change.envVersion() == null || change.envVersion().isBlank()) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID, "envVersion 不能为空");
        }
        Instant now = clock.instant();
        Instant since = now.minus(Duration.ofDays(props.getExperiment().getTrafficWindowDays()));
        int queued = 0;
        for (Long itemId : outcomes.topItems(change.tenantId(), since,
                props.getRevalidate().getMaxItemsPerChange())) {
            Item item = items.find(itemId).orElse(null);
            if (item == null || !ItemStore.STATUS_ACTIVE.equals(item.status())
                    || change.envVersion().equals(item.validatedEnvVersion())) {
                continue;
            }
            if (jobs.enqueue(JobQueue.Type.REVALIDATE, "REVAL:" + itemId, item.tenantId(), itemId,
                    null, write(new Payload(change.envVersion(), null)),
                    Trigger.REVALIDATION.priority(), now, now)) {
                queued++;
            }
        }
        audit.audit(change.tenantId(), actor, "ENVIRONMENT_REGISTERED", "ENVIRONMENT",
                change.envVersion(), Map.of("queued", queued), now);
        return queued;
    }

    private boolean eligible(JobQueue.Job job, Instant now) {
        if (job.tenantId() == null) {
            return true;
        }
        if (!policy.of(job.tenantId()).evolves()) {
            return false;
        }
        int running = EVAL_TYPES.stream()
                .mapToInt(t -> jobs.runningForTenant(t, job.tenantId(), now)).sum();
        return running < props.getJobs().getMaxConcurrentEvalsPerTenant();
    }

    private Optional<EvalJob> pairwise(JobQueue.Job job, Instant now) {
        Proposal p = job.proposalId() == null ? null : proposals.find(job.proposalId()).orElse(null);
        Item item = p == null ? null : items.find(p.itemId()).orElse(null);
        if (p == null || item == null || p.state() != ProposalState.EVALUATING) {
            jobs.complete(job, now);
            return Optional.empty();
        }
        Revision base = items.revision(p.baseRevisionId()).orElseThrow();
        Revision candidate = items.revision(p.candidateRevisionId()).orElseThrow();
        Payload payload = read(job.payloadJson());
        if (payload == null || payload.cases() == null) {
            EvolutionProperties.Eval e = props.getEval();
            List<EvidenceCases.Case> triggers = evidence.triggers(p,
                    e.getMaxPairwiseCases() - e.getMinSuccessCases());
            List<EvidenceCases.Case> cases = new ArrayList<>(triggers);
            cases.addAll(evidence.successes(base.id(), e.getMaxPairwiseCases() - triggers.size()));
            cases.addAll(evidence.regressions(item.id(), p.fingerprint(),
                    e.getMaxRegressionCases()));
            payload = new Payload(null, cases.stream().map(EvidenceCases.Case::toEval).toList());
            jobs.setPayload(job, write(payload));
        }
        return Optional.of(new EvalJob(job.id(), job.leaseToken(), p.id(), EvalMode.PAIRWISE,
                item.name(), base.content(), candidate.content(), payload.cases()));
    }

    private Optional<EvalJob> revalidation(JobQueue.Job job, Instant now) {
        Item item = job.itemId() == null ? null : items.find(job.itemId()).orElse(null);
        Payload payload = read(job.payloadJson());
        if (item == null || item.activeRevisionId() == null || payload == null
                || !ItemStore.STATUS_ACTIVE.equals(item.status())) {
            jobs.complete(job, now);
            return Optional.empty();
        }
        if (payload.cases() == null) {
            int max = props.getRevalidate().getMaxCasesPerItem();
            List<EvidenceCases.Case> cases = new ArrayList<>(
                    evidence.regressions(item.id(), null, max));
            cases.addAll(evidence.successes(item.activeRevisionId(), max - cases.size()));
            if (cases.isEmpty()) {
                items.setValidatedEnv(item.id(), payload.envVersion());
                jobs.complete(job, now);
                return Optional.empty();
            }
            payload = new Payload(payload.envVersion(),
                    cases.stream().map(EvidenceCases.Case::toEval).toList());
            jobs.setPayload(job, write(payload));
        }
        Revision active = items.revision(item.activeRevisionId()).orElseThrow();
        return Optional.of(new EvalJob(job.id(), job.leaseToken(), null, EvalMode.REVALIDATE,
                item.name(), null, active.content(), payload.cases()));
    }

    /**
     * 通过线：触发案例修复率 ≥ pass-fix-rate；成功案例退化率 ≤ max-regression-rate；
     * 回归案例复发率 ≤ max-regression-set-failure-rate；候选编译失败数不多于基线；
     * 判错占比 ≤ max-error-rate（本实现新增，判错过多视为评估无效）。
     */
    private void completePairwise(JobQueue.Job job, Tally t, EvalResult result) {
        Proposal p = proposals.find(job.proposalId()).orElse(null);
        if (p == null || p.state() != ProposalState.EVALUATING) {
            return;
        }
        Item item = items.find(p.itemId()).orElseThrow();
        EvolutionProperties.Eval e = props.getEval();
        double fixRate = t.rate(CaseKind.TRIGGER, Verdict.WIN);
        double successLoss = t.rate(CaseKind.SUCCESS, Verdict.LOSS);
        double regressionFail = t.rate(CaseKind.REGRESSION, Verdict.LOSS);
        double errorRate = t.all() == 0 ? 1 : (double) t.errors() / t.all();
        boolean smallSample = t.total(CaseKind.SUCCESS) < e.getMinSuccessCases();
        String failure = t.judged(CaseKind.TRIGGER) == 0 ? "没有可判定的触发案例"
                : errorRate > e.getMaxErrorRate() ? "判错过多，评估无效"
                : fixRate < e.getPassFixRate() ? "触发案例修复率不足"
                : successLoss > e.getMaxRegressionRate() ? "成功案例退化率超限"
                : regressionFail > e.getMaxRegressionSetFailureRate() ? "回归案例复发率超限"
                : t.candidateCompileFailures() > t.baselineCompileFailures() ? "候选编译失败多于基线"
                : null;
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("fixRate", fixRate);
        summary.put("successLossRate", successLoss);
        summary.put("regressionFailureRate", regressionFail);
        summary.put("errorRate", errorRate);
        summary.put("triggerCases", t.total(CaseKind.TRIGGER));
        summary.put("successCases", t.total(CaseKind.SUCCESS));
        summary.put("regressionCases", t.total(CaseKind.REGRESSION));
        summary.put("baselineCompileFailures", t.baselineCompileFailures());
        summary.put("candidateCompileFailures", t.candidateCompileFailures());
        summary.put("smallSample", smallSample);
        summary.put("plannerModel", result.plannerModel());
        summary.put("judgeVersion", result.judgeVersion());
        String evalJson = write(summary);
        proposals.addTokens(p.id(), Math.max(0, result.tokensUsed()));
        Instant now = clock.instant();
        metrics.counter("evo_eval_total", "passed", String.valueOf(failure == null)).increment();
        if (failure != null) {
            proposals.evaluated(p.id(), evalJson, false, ProposalState.EVAL_FAILED, failure, null,
                    now);
            return;
        }
        EvolutionPolicy.Mode mode = policy.of(p.tenantId()).mode();
        if (mode == EvolutionPolicy.Mode.SHADOW) {
            proposals.evaluated(p.id(), evalJson, true, ProposalState.SHADOW_RECORDED,
                    "SHADOW 模式：离线通过，仅记录", null, now);
        } else if (mode == EvolutionPolicy.Mode.AUTO && !smallSample
                && experiments.trafficSufficient(item)) {
            proposals.evaluated(p.id(), evalJson, true, ProposalState.EVALUATING, "离线通过",
                    null, now);
            Proposal ready = proposals.find(p.id()).orElseThrow();
            if (props.getExperiment().isRequireLiveArms()) {
                experiments.start(ready, item, ProposalState.EVALUATING, null);
            } else {
                experiments.promote(ready, item, ProposalState.EVALUATING,
                        "离线通过，写入当前修订", null, false);
            }
        } else {
            String reason = mode == EvolutionPolicy.Mode.ASSISTED ? "离线通过，等待审批"
                    : smallSample ? "离线通过但成功案例不足，转人工审批" : "离线通过但流量不足，转人工审批";
            proposals.evaluated(p.id(), evalJson, true, ProposalState.APPROVAL_PENDING, reason,
                    experiments.approvalExpiry(now), now);
        }
        log.info("离线评估完成 proposal={} fixRate={} passed=true mode={}", p.id(), fixRate, mode);
    }

    /** 旧环境下这些案例都是通过的（成功案例 / 已修复的回归案例），通过率降幅 = 新环境下的失败占比。 */
    private void completeRevalidation(JobQueue.Job job, Payload payload, Tally t,
                                      EvalResult result) {
        Item item = items.find(job.itemId()).orElse(null);
        int judged = t.judged(CaseKind.SUCCESS) + t.judged(CaseKind.REGRESSION);
        if (item == null || judged == 0) {
            return;
        }
        int lost = t.count(CaseKind.SUCCESS, Verdict.LOSS) + t.count(CaseKind.REGRESSION, Verdict.LOSS);
        double drop = (double) lost / judged;
        Instant now = clock.instant();
        if (drop <= props.getRevalidate().getMaxDrop()) {
            items.setValidatedEnv(item.id(), payload.envVersion());
        } else {
            items.setHealth(item.id(), ItemStore.HEALTH_DEGRADED);
            Map<String, EvalCase> issued = payload.cases().stream()
                    .collect(Collectors.toMap(EvalCase::caseId, Function.identity(), (a, b) -> a));
            List<EvalCase> failed = result.verdicts().stream()
                    .filter(v -> v.verdict() == Verdict.LOSS && issued.containsKey(v.caseId()))
                    .map(v -> issued.get(v.caseId())).toList();
            Map<String, Object> evidenceJson = new LinkedHashMap<>();
            evidenceJson.put("envVersion", payload.envVersion());
            evidenceJson.put("drop", drop);
            evidenceJson.put("cases", EvidenceCases.asTriggers(failed));
            detector.open(item, Trigger.REVALIDATION, null, evidenceJson);
        }
        audit.audit(item.tenantId(), "revalidation", "ITEM_REVALIDATED", "ITEM", item.id(),
                Map.of("envVersion", payload.envVersion(), "drop", drop,
                        "plannerModel", String.valueOf(result.plannerModel())), now);
    }

    /** 未回写的下发案例记 ERROR；未下发过的案例 id 忽略。 */
    static Tally tally(List<EvalCase> issued, List<CaseVerdict> verdicts) {
        Map<String, CaseVerdict> byId = verdicts == null ? Map.of() : verdicts.stream()
                .filter(v -> v.caseId() != null && v.verdict() != null)
                .collect(Collectors.toMap(CaseVerdict::caseId, Function.identity(), (a, b) -> a));
        Map<CaseKind, Map<Verdict, Integer>> counts = new EnumMap<>(CaseKind.class);
        int baselineFailures = 0;
        int candidateFailures = 0;
        for (EvalCase c : issued) {
            CaseVerdict v = byId.get(c.caseId());
            Verdict verdict = v == null ? Verdict.ERROR : v.verdict();
            counts.computeIfAbsent(c.kind(), k -> new EnumMap<>(Verdict.class))
                    .merge(verdict, 1, Integer::sum);
            if (v != null && c.kind() != CaseKind.REGRESSION) {
                baselineFailures += v.baselineCompiled() ? 0 : 1;
                candidateFailures += v.candidateCompiled() ? 0 : 1;
            }
        }
        return new Tally(counts, baselineFailures, candidateFailures);
    }

    private Payload read(String payloadJson) {
        if (payloadJson == null) {
            return null;
        }
        try {
            return json.readValue(payloadJson, Payload.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
