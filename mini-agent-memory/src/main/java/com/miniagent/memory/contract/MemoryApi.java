package com.miniagent.memory.contract;

import java.time.Instant;
import java.util.List;

/**
 * memory-service {@code /internal/v2} 协议：路径、枚举与报文。
 * App 客户端、服务端控制器、评估 worker 引用同一份定义，避免两端各写一份字面量。
 */
public final class MemoryApi {
    private MemoryApi() {
    }

    public static final String V2 = "/internal/v2";
    public static final String MEMORY = V2 + "/memory";
    public static final String ASSEMBLE = MEMORY + "/context:assemble";
    public static final String OUTCOMES = MEMORY + "/outcomes:batch";
    public static final String FEEDBACK = MEMORY + "/feedback";
    public static final String ITEMS = MEMORY + "/items";
    public static final String ITEMS_IMPORT = MEMORY + "/items:import";
    public static final String CHANGES = MEMORY + "/changes";
    public static final String PROPOSALS = MEMORY + "/proposals";
    public static final String PROJECTS = MEMORY + "/projects";
    public static final String SUBJECTS = MEMORY + "/subjects";
    public static final String EVAL_LEASE = V2 + "/eval/jobs:lease";
    public static final String EVAL_JOBS = V2 + "/eval/jobs";
    public static final String ADMIN = V2 + "/admin";

    public enum ScopeType { USER, PROJECT }

    /** BASELINE = 对照臂（旧修订），CANDIDATE = 实验臂（候选修订）。 */
    public enum Arm { BASELINE, CANDIDATE }

    public enum OutcomeResult { SUCCESS, FAILURE, FEEDBACK_UP, FEEDBACK_DOWN }

    public enum AckStatus { ACCEPTED, DUPLICATE, REJECTED }

    /** TRIGGER = 触发提案的失败案例；SUCCESS = 基线成功案例；REGRESSION = 历史修复过的案例。 */
    public enum CaseKind { TRIGGER, SUCCESS, REGRESSION }

    /**
     * 以候选为主语。成对比较：WIN = 候选计划更好；单方判定（回归案例、重验）：
     * WIN = 计划能完成目标且不重现记录的失败，LOSS = 不能。TIE 只出现在成对比较。
     */
    public enum Verdict { WIN, LOSS, TIE, ERROR }

    /**
     * PAIRWISE：触发与成功案例做「基线修订 vs 候选修订」成对比较（正反两种顺序），
     * 回归案例只回放候选做单方判定。REVALIDATE：在当前环境下只回放当前修订，逐案单方判定。
     */
    public enum EvalMode { PAIRWISE, REVALIDATE }

    // ---------- 读路径 ----------

    public record AssembleRequest(String taskKey, String query) {
    }

    public record AssembledProcedure(long itemId, int revisionNo, ScopeType scopeType, String name,
                                     ProcedureContent content, Long proposalId,
                                     String candidateHash, Arm arm, int trafficPercent) {
    }

    public record AssembleResponse(List<AssembledProcedure> procedures, boolean holdout) {
    }

    // ---------- 结果回传 ----------

    public record OutcomeEvent(String eventId, String tenantId, long userId, String projectId,
                               long itemId, int revisionNo, Long proposalId, Arm arm,
                               int trafficPercent, String taskKey, String nodeKey,
                               OutcomeResult result, String errorCode, String failureReason,
                               String userMessage, String nodeName, String envVersion,
                               Instant occurredAt) {
    }

    public record OutcomeBatch(List<OutcomeEvent> events) {
    }

    public record OutcomeAck(String eventId, AckStatus status, String code) {
    }

    public record OutcomeBatchResult(List<OutcomeAck> results) {
    }

    public record FeedbackRequest(String taskKey, boolean positive) {
    }

    // ---------- 条目 ----------

    public record ItemWrite(ScopeType scopeType, String scopeId, String name,
                            ProcedureContent content, Long legacyProcedureId) {
    }

    public record ItemImport(String tenantId, long ownerUserId, ItemWrite item) {
    }

    public record ItemView(long id, ScopeType scopeType, String scopeId, String name,
                           String status, int revisionNo, ProcedureContent content,
                           long activationVersion, boolean evolutionLocked, String health,
                           Instant updatedAt) {
    }

    public record ItemPage(List<ItemView> items, Long nextCursor) {
    }

    public record RevisionView(long id, int revisionNo, String origin, ProcedureContent content,
                               String contentHash, Long proposalId, String createdBy,
                               Instant createdAt) {
    }

    public record ChangeView(long id, long itemId, String itemName, Integer fromRevisionNo,
                             int toRevisionNo, String reason, Long proposalId, String actor,
                             Instant createdAt) {
    }

    public record RevertRequest(int revisionNo) {
    }

    public record LockRequest(boolean locked) {
    }

    public record ItemHealth(long itemId, int revisionNo, long recentTasks, long recentSuccesses,
                             long baselineTasks, long baselineSuccesses, String health) {
    }

    // ---------- 提案与审批 ----------

    /** stateReason：进入当前状态的原因（被拒 / 回滚 / 转审批的说明），给用户看。 */
    public record ProposalView(long id, long itemId, String itemName, String state,
                               String stateReason, String triggerType, int baseRevisionNo,
                               Integer candidateRevisionNo,
                               ProcedureContent baseline, ProcedureContent candidate,
                               String rationale, String evalSummary, int trafficPercent,
                               String decisionSummary, Instant createdAt, Instant updatedAt) {
    }

    public record ReviewRequest(String note) {
    }

    // ---------- 评估 worker ----------

    public record EvalLeaseRequest(String workerId) {
    }

    public record EvalCase(String caseId, CaseKind kind, String userMessage,
                           String failureReason) {
    }

    /** proposalId 在 REVALIDATE 时为空；baseline 在 REVALIDATE 时为空。 */
    public record EvalJob(long jobId, String leaseToken, Long proposalId, EvalMode mode,
                          String itemName, ProcedureContent baseline,
                          ProcedureContent candidate, List<EvalCase> cases) {
    }

    /** baselineCompiled / candidateCompiled：该侧计划是否编译成功（单方判定时 baseline 恒为 false）。 */
    public record CaseVerdict(String caseId, Verdict verdict, boolean baselineCompiled,
                              boolean candidateCompiled, String note) {
    }

    public record EvalResult(String leaseToken, List<CaseVerdict> verdicts, long tokensUsed,
                             String plannerModel, String judgeVersion) {
    }

    // ---------- 运维 ----------

    public record PolicyUpdate(String mode, Long dailyTokenBudget, Integer holdoutBps) {
    }

    /** tenantId 为空表示全局。 */
    public record FreezeRequest(String tenantId, String reason) {
    }

    public record RollbackSinceRequest(String tenantId, Instant since, String reason) {
    }

    public record ReviewVerdict(long proposalId, boolean agree, String note) {
    }

    /** 发布流水线在上线新规划模型 / 工具 schema / 提示词模板前登记；tenantId 为空表示全部租户。 */
    public record EnvironmentChange(String tenantId, String envVersion) {
    }
}
