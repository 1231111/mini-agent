package com.miniagent.memoryservice.web;

import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.common.exception.BusinessException;
import com.miniagent.memory.contract.MemoryApi;
import com.miniagent.memory.contract.MemoryApi.EnvironmentChange;
import com.miniagent.memory.contract.MemoryApi.FreezeRequest;
import com.miniagent.memory.contract.MemoryApi.PolicyUpdate;
import com.miniagent.memory.contract.MemoryApi.ProposalView;
import com.miniagent.memory.contract.MemoryApi.ReviewVerdict;
import com.miniagent.memory.contract.MemoryApi.RollbackSinceRequest;
import com.miniagent.memory.contract.ServiceToken.Principal;
import com.miniagent.memoryservice.evolution.EvalCoordinator;
import com.miniagent.memoryservice.evolution.EvolutionPolicy;
import com.miniagent.memoryservice.evolution.ExperimentService;
import com.miniagent.memoryservice.evolution.ReportService;
import com.miniagent.memoryservice.store.AuditStore;
import com.miniagent.memoryservice.store.JobQueue;
import com.miniagent.memoryservice.store.PolicyStore;
import com.miniagent.memoryservice.store.ProposalStore;
import com.miniagent.memoryservice.evolution.Stats;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.miniagent.memoryservice.web.ServiceTokenFilter.PRINCIPAL_ATTR;

/** 运维接口（服务令牌 role=admin）：策略、冻结、批量回滚、环境登记、报表、抽检与死信重试。 */
@RestController
public class AdminController {
    private static final String ADMIN = MemoryApi.ADMIN;
    private static final String DEFAULT_AUDIT_LIMIT = "100";
    /** 抽检只从最近 30 天的晋升里挑。 */
    private static final Duration REVIEW_WINDOW = Duration.ofDays(30);

    private final PolicyStore policies;
    private final EvolutionPolicy policy;
    private final ExperimentService experiments;
    private final EvalCoordinator evals;
    private final ReportService reports;
    private final ProposalStore proposals;
    private final AuditStore audit;
    private final JobQueue jobs;
    private final Clock clock;

    public AdminController(PolicyStore policies, EvolutionPolicy policy,
                           ExperimentService experiments, EvalCoordinator evals,
                           ReportService reports, ProposalStore proposals, AuditStore audit,
                           JobQueue jobs, Clock clock) {
        this.policies = policies;
        this.policy = policy;
        this.experiments = experiments;
        this.evals = evals;
        this.reports = reports;
        this.proposals = proposals;
        this.audit = audit;
        this.jobs = jobs;
        this.clock = clock;
    }

    @GetMapping(ADMIN + "/policies/{tenantId}")
    public ApiResponse<EvolutionPolicy.Effective> getPolicy(@PathVariable String tenantId) {
        return ApiResponse.ok(policy.of(tenantId));
    }

    @PutMapping(ADMIN + "/policies/{tenantId}")
    public ApiResponse<EvolutionPolicy.Effective> putPolicy(
            @RequestAttribute(PRINCIPAL_ATTR) Principal principal,
            @PathVariable String tenantId, @RequestBody PolicyUpdate update) {
        validate(update);
        String mode = update.mode() == null ? null
                : EvolutionPolicy.Mode.valueOf(update.mode()).name();
        policies.upsert(tenantId, mode, update.dailyTokenBudget(), update.holdoutBps(),
                actor(principal), clock.instant());
        audit.audit(tenantId, actor(principal), "POLICY_UPDATED", "TENANT", tenantId,
                Map.of("update", update), clock.instant());
        return ApiResponse.ok(policy.of(tenantId));
    }

    /** tenantId 为空 = 全局冻结：读路径只给基线（实验中）或活动修订，不做任何决策与自动回滚。 */
    @PostMapping(ADMIN + "/evolution:freeze")
    public ApiResponse<Void> freeze(@RequestAttribute(PRINCIPAL_ATTR) Principal principal,
                                    @RequestBody FreezeRequest request) {
        setFrozen(principal, request, true);
        return ApiResponse.ok();
    }

    @PostMapping(ADMIN + "/evolution:unfreeze")
    public ApiResponse<Void> unfreeze(@RequestAttribute(PRINCIPAL_ATTR) Principal principal,
                                      @RequestBody FreezeRequest request) {
        setFrozen(principal, request, false);
        return ApiResponse.ok();
    }

    /** 把某时刻之后的所有进化晋升回滚到晋升前的修订；tenantId 为空表示全部租户。 */
    @PostMapping(ADMIN + "/evolution:rollback-since")
    public ApiResponse<Integer> rollbackSince(
            @RequestAttribute(PRINCIPAL_ATTR) Principal principal,
            @RequestBody RollbackSinceRequest request) {
        if (request == null || request.since() == null) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID, "缺少 since");
        }
        return ApiResponse.ok(experiments.rollbackSince(request.tenantId(), request.since(),
                request.reason(), actor(principal)));
    }

    /** 发布流水线登记新环境版本，为该租户用量最高的条目排重验作业。 */
    @PostMapping(ADMIN + "/environment-versions")
    public ApiResponse<Integer> registerEnvironment(
            @RequestAttribute(PRINCIPAL_ATTR) Principal principal,
            @RequestBody EnvironmentChange change) {
        if (change == null || change.envVersion() == null || change.envVersion().isBlank()) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID, "缺少 envVersion");
        }
        return ApiResponse.ok(evals.registerEnvironment(change, actor(principal)));
    }

    @GetMapping(ADMIN + "/slo")
    public ApiResponse<Map<String, Object>> slo(@RequestParam String tenantId) {
        return ApiResponse.ok(reports.slo(tenantId));
    }

    @GetMapping(ADMIN + "/holdout")
    public ApiResponse<Map<String, Object>> holdout(@RequestParam String tenantId) {
        return ApiResponse.ok(reports.holdout(tenantId));
    }

    /** 下一个待抽检的晋升（最近一次尚无人工判定的）；没有时 data 为 null。 */
    @GetMapping(ADMIN + "/reviews:next")
    public ApiResponse<ProposalView> nextReview() {
        return ApiResponse.ok(proposals.latestUnreviewedPromotion(
                clock.instant().minus(REVIEW_WINDOW)).map(experiments::view).orElse(null));
    }

    @PostMapping(ADMIN + "/reviews")
    public ApiResponse<Void> review(@RequestAttribute(PRINCIPAL_ATTR) Principal principal,
                                    @RequestBody ReviewVerdict verdict) {
        if (verdict == null || proposals.find(verdict.proposalId()).isEmpty()) {
            throw new BusinessException(ErrorCode.MEMORY_REVIEW_NOT_FOUND);
        }
        audit.review(verdict.proposalId(), actor(principal), verdict.agree(), verdict.note(),
                clock.instant());
        return ApiResponse.ok();
    }

    @GetMapping(ADMIN + "/jobs")
    public ApiResponse<Map<String, Long>> jobDepth() {
        return ApiResponse.ok(jobs.depthByTypeAndStatus());
    }

    @PostMapping(ADMIN + "/jobs/{id:\\d+}:retry")
    public ApiResponse<Boolean> retry(@PathVariable long id) {
        return ApiResponse.ok(jobs.retryDead(id, clock.instant()));
    }

    @GetMapping(ADMIN + "/audit")
    public ApiResponse<List<Map<String, Object>>> audit(
            @RequestParam String tenantId,
            @RequestParam(defaultValue = DEFAULT_AUDIT_LIMIT) int limit) {
        return ApiResponse.ok(audit.recentAudit(tenantId, limit));
    }

    private void setFrozen(Principal principal, FreezeRequest request, boolean frozen) {
        String tenant = request == null || request.tenantId() == null || request.tenantId().isBlank()
                ? PolicyStore.GLOBAL : request.tenantId();
        String reason = request == null ? null : request.reason();
        Instant now = clock.instant();
        policies.setFrozen(tenant, frozen, reason, actor(principal), now);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reason", reason);
        audit.audit(tenant, actor(principal), frozen ? "EVOLUTION_FROZEN" : "EVOLUTION_UNFROZEN",
                "TENANT", tenant, detail, now);
    }

    private static void validate(PolicyUpdate update) {
        if (update == null) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID, "缺少策略");
        }
        boolean badMode = update.mode() != null && Arrays.stream(EvolutionPolicy.Mode.values())
                .noneMatch(m -> m.name().equals(update.mode()));
        boolean badBudget = update.dailyTokenBudget() != null && update.dailyTokenBudget() < 0;
        boolean badHoldout = update.holdoutBps() != null
                && (update.holdoutBps() < 0 || update.holdoutBps() >= Stats.BUCKETS);
        if (badMode || badBudget || badHoldout) {
            throw new BusinessException(ErrorCode.MEMORY_REQUEST_INVALID,
                    "mode 须为 OFF/SHADOW/ASSISTED/AUTO，预算 ≥ 0，留存万分比在 [0,10000)");
        }
    }

    private static String actor(Principal principal) {
        return principal.userId() == null ? principal.issuer()
                : principal.issuer() + ":" + principal.userId();
    }
}
