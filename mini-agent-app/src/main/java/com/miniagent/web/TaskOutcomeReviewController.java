package com.miniagent.web;

import com.miniagent.agent.core.UserFacingFailure;
import com.miniagent.agent.core.AgentLoop;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.TaskExecutionContext;
import com.miniagent.agent.planner.PlannerStateStore;
import com.miniagent.agent.planner.StateSnapshot;
import com.miniagent.agent.planner.TaskNodeStatus;
import com.miniagent.agent.planner.UnknownOutcomeReconciler;
import com.miniagent.agent.tool.ToolStatus;
import com.miniagent.application.TaskProgressSnapshot;
import com.miniagent.application.ConversationWorkspaceService;
import com.miniagent.application.ConversationTaskProgressService;
import com.miniagent.agent.tool.WorkspaceContext;
import com.miniagent.agent.browser.BrowserService;
import com.miniagent.agent.browser.BrowserSessionIdentity;
import com.miniagent.agent.execution.ToolExecutionGuards;
import com.miniagent.common.ApiResponse;
import com.miniagent.common.ErrorCode;
import com.miniagent.config.model.UserModelBinder;
import com.miniagent.config.security.AuthenticatedUser;
import com.miniagent.config.security.JwtSessionService;
import com.miniagent.config.security.SessionAuthorizationService;
import com.miniagent.config.service.TaskRunService;
import com.miniagent.config.service.TaskRunLeaseRenewer;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Owner-only, explicitly confirmed human review; not registered with any agent tool surface. */
@RestController
@RequestMapping("/api/conversations/{sessionId}/outcome-review")
public class TaskOutcomeReviewController {
    private static final Logger LOG = LoggerFactory.getLogger(TaskOutcomeReviewController.class);
    public record Node(String id, String name, String reason) { }
    public record Review(String executionId, long version, long planVersion, List<Node> nodes,
                         TaskProgressSnapshot progress) { }

    private final SessionAuthorizationService authorization;
    private final PlannerStateStore states;
    private final UnknownOutcomeReconciler reconciler;
    private final UserModelBinder binder;
    private final TaskRunService runs;
    private final ConversationWorkspaceService workspaces;
    private final ToolExecutionGuards tools;
    private final BrowserService browser;
    private final ConversationTaskProgressService taskProgress;
    private final ExecutionControl executionControl;
    private final TaskRunLeaseRenewer leaseRenewer;

    public TaskOutcomeReviewController(SessionAuthorizationService authorization, PlannerStateStore states,
                                      UnknownOutcomeReconciler reconciler, UserModelBinder binder,
                                      TaskRunService runs, ConversationWorkspaceService workspaces,
                                      ToolExecutionGuards tools, BrowserService browser,
                                      ConversationTaskProgressService taskProgress,
                                      ExecutionControl executionControl, TaskRunLeaseRenewer leaseRenewer) {
        this.authorization = authorization;
        this.states = states;
        this.reconciler = reconciler;
        this.binder = binder;
        this.runs = runs;
        this.workspaces = workspaces;
        this.tools = tools;
        this.browser = browser;
        this.taskProgress = taskProgress;
        this.executionControl = executionControl;
        this.leaseRenewer = leaseRenewer;
    }

    @GetMapping
    public ApiResponse<Review> inspect(@PathVariable String sessionId, HttpServletRequest request) {
        AuthenticatedUser user = principal(request);
        if (user == null) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        authorization.requireOwner(user.userId(), sessionId);
        try (var owner = binder.bindOwner(user.userId(), String.valueOf(user.tenantId()))) {
            return states.get(sessionId).map(snapshot -> ApiResponse.ok(view(snapshot)))
                    .orElseGet(() -> ApiResponse.fail(ErrorCode.CONFIG_INVALID, "当前没有可核验的任务。"));
        }
    }

    @PostMapping
    public ApiResponse<Review> resolve(@PathVariable String sessionId,
                                      @RequestBody UnknownOutcomeReconciler.Request body,
                                      HttpServletRequest request) {
        AuthenticatedUser user = principal(request);
        if (user == null) return ApiResponse.fail(ErrorCode.AUTH_NOT_AUTHENTICATED);
        authorization.requireOwner(user.userId(), sessionId);
        if (body == null || !body.confirmed())
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, "请先核验实际结果并明确确认。");
        TaskRunService.StartResult admission = runs.tryStart(user.userId(), sessionId);
        if (!admission.accepted()) return ApiResponse.fail("TASK_BUSY", "任务正在执行或核验中，请稍后再试。");
        boolean committed = false;
        try (var owner = binder.bindOwner(user.userId(), String.valueOf(user.tenantId()));
             var workspace = bindWorkspace(sessionId, user.userId());
             var control = bindReviewRun(sessionId, user.tenantId(), admission.handle());
             var model = body.decision() == UnknownOutcomeReconciler.Decision.CONFIRMED_APPLIED
                     ? binder.bindModel(user.userId()) : UserModelBinder.Binding.noop()) {
            requireIdle(sessionId);
            StateSnapshot result = reconciler.reconcile(sessionId, user.userId(), body, () -> {
                if (executionControl.stopReason(sessionId) != ExecutionControl.StopReason.NONE)
                    throw new IllegalStateException("核验已停止，尚未修改原任务状态。");
                if (!runs.renewSessionLock(sessionId, admission.handle().fencingToken()))
                    throw new IllegalStateException("核验租约已失效，请刷新后重试。");
                requireIdle(sessionId);
            });
            committed = true;
            try { taskProgress.persistReviewedOutcome(user.userId(), sessionId, result); }
            catch (RuntimeException projectionFailure) {
                // Planner already committed. Its durable review receipts let message
                // reads repair this exact execution's card after a DB/transient failure.
                LOG.warn("Outcome review committed; historical task-card projection deferred session={} execution={}",
                        sessionId, result.executionId(), projectionFailure);
            }
            return ApiResponse.ok(view(result));
        } catch (PlannerStateStore.VersionConflictException conflict) {
            return ApiResponse.fail("STATE_CONFLICT", "任务状态已变化，请刷新后重新核验。");
        } catch (java.util.concurrent.CancellationException stopped) {
            return ApiResponse.fail("REVIEW_STOPPED", "核验已停止，原步骤仍待核验；请稍后重新读取任务状态。");
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return ApiResponse.fail(ErrorCode.CONFIG_INVALID, UserFacingFailure.summarize(
                    invalid.getMessage(), "核验未能完成，请查看执行日志。"));
        } finally {
            // A successful review is not a completed business task. A later user turn
            // resumes the original DAG; no tool is dispatched by this endpoint.
            try {
                if (committed) runs.markWaiting(admission.handle(), "OUTCOME_REVIEWED");
                else runs.markFailed(admission.handle(), "OUTCOME_REVIEW_REJECTED");
            } catch (RuntimeException finishFailure) {
                LOG.error("Outcome review lease finalization failed session={} committed={}",
                        sessionId, committed, finishFailure);
            }
        }
    }

    private static Review view(StateSnapshot snapshot) {
        List<Node> nodes = snapshot.graph().nodes().stream()
                .filter(n -> n.status() == TaskNodeStatus.OUTCOME_UNKNOWN || n.toolStatus() == ToolStatus.UNKNOWN)
                .map(n -> new Node(n.id(), n.name(), UserFacingFailure.summarize(n.lastError(),
                        "操作结果尚未确认，请核验实际页面或业务回执。"))).toList();
        return new Review(snapshot.executionId(), snapshot.version(), snapshot.planVersion(), nodes,
                TaskProgressSnapshot.fromGraph(snapshot));
    }

    private static AuthenticatedUser principal(HttpServletRequest request) {
        Object principal = request.getAttribute(JwtSessionService.ATTR_PRINCIPAL);
        return principal instanceof AuthenticatedUser user ? user : null;
    }

    private AutoCloseableWorkspace bindWorkspace(String sessionId, Long userId) {
        var root = workspaces.effectiveRoot(sessionId, userId);
        var previousRoot = WorkspaceContext.getRootOverride();
        var previousSession = WorkspaceContext.getSessionOverride();
        var previousTask = WorkspaceContext.getTaskOverride();
        var previousOperationRoot = WorkspaceContext.getOperationRootSession();
        WorkspaceContext.setRootOverride(root);
        WorkspaceContext.setSessionOverride(sessionId);
        WorkspaceContext.setOperationRootSession(sessionId);
        WorkspaceContext.clearTaskOverride();
        return () -> {
            if (previousRoot == null) WorkspaceContext.clearRootOverride();
            else WorkspaceContext.setRootOverride(previousRoot);
            WorkspaceContext.setSessionOverride(previousSession);
            WorkspaceContext.setOperationRootSession(previousOperationRoot);
            if (previousTask == null) WorkspaceContext.clearTaskOverride();
            else WorkspaceContext.setTaskOverride(previousTask);
        };
    }

    /** Judge calls share normal cancellation, quota accounting and lease renewal. */
    private AutoCloseableWorkspace bindReviewRun(String sessionId, Long tenantId, TaskRunService.RunHandle run) {
        String previousSession = AgentLoop.getCurrentSession();
        var task = TaskExecutionContext.bindRun(run.fencingToken());
        TaskRunLeaseRenewer.Guard lease = null;
        try {
            AgentLoop.setCurrentSession(sessionId);
            executionControl.start(sessionId, tenantId, run.fencingToken());
            lease = leaseRenewer.start(run);
        } catch (RuntimeException failure) {
            executionControl.finish(sessionId, run.fencingToken());
            task.close();
            if (previousSession == null) AgentLoop.clearCurrentSession(); else AgentLoop.setCurrentSession(previousSession);
            throw failure;
        }
        TaskRunLeaseRenewer.Guard active = lease;
        return () -> {
            try { if (active != null) active.close(); }
            finally {
                executionControl.finish(sessionId, run.fencingToken());
                task.close();
                if (previousSession == null) AgentLoop.clearCurrentSession(); else AgentLoop.setCurrentSession(previousSession);
            }
        };
    }

    private void requireIdle(String sessionId) {
        if (tools.hasActiveHandlers(sessionId) || browser.hasPendingOperations(BrowserSessionIdentity.resolve(sessionId))) {
            throw new IllegalStateException("上一次工具操作仍在收尾，请等待其退出后再核验，暂不允许重新执行。");
        }
    }

    private interface AutoCloseableWorkspace extends AutoCloseable {
        @Override void close();
    }
}
