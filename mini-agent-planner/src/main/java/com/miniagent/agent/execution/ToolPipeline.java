package com.miniagent.agent.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.miniagent.agent.coding.DiagnosticCollector;
import com.miniagent.agent.desktop.DesktopInteractionService;
import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ExecutionTurnContext;
import com.miniagent.agent.core.RunScope;
import com.miniagent.agent.core.SessionEventCenter;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.hook.ToolHookContext;
import com.miniagent.agent.hook.ToolPreDecision;
import com.miniagent.agent.permission.ExecPolicyService;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.permission.PermissionPolicy;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.permission.AskGrant;
import com.miniagent.agent.tool.AskUserQuestionTool;
import com.miniagent.agent.tool.BuiltinTools;
import com.miniagent.agent.tool.ToolConcurrencyPolicy;
import com.miniagent.agent.tool.ToolDescriptor;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolSideEffect;
import com.miniagent.agent.tool.ToolStatus;
import com.miniagent.agent.tool.impl.ExecCommandParams;
import com.miniagent.agent.trace.AgentStepNode;
import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.common.MessageConstants;
import com.miniagent.common.RunStatus;
import com.miniagent.common.permission.ExecPolicy;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.miniagent.agent.core.ExecutionProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具执行唯一入口。ReAct 循环与规划器绑定动作走同一条管道：
 * 控制面 → fence → 探测限额 → 硬闸门 → 权限 → Hook → Journal → Registry。
 */
@Component
@EnableConfigurationProperties(ExecutionProperties.class)
public class ToolPipeline {

    private static final Logger log = LoggerFactory.getLogger(ToolPipeline.class);
    private static final String BROWSER_EVALUATE = "browser_evaluate";

    /** 只为解析工具参数里的 {@code path}，不复用全局 ObjectMapper：这里不需要任何定制。 */
    private static final ObjectMapper ARGS_JSON = new ObjectMapper();

    private final ExecutionControl executionControl;
    private final ToolHookChain toolHookChain;
    private final SessionPermissionStore permissionStore;
    private final ActionJournal actionJournal;
    private final ToolExecutionGuards toolExecutionGuards;
    private final ToolRegistry toolRegistry;
    /**
     * exec_command 策略。之前这里是一个构造器注入的 {@code final boolean execEnabled} ——
     * 启动后改不了，而用户需要在会话里随时切换，所以改成走 {@link ExecPolicyService}。
     */
    private final ExecPolicyService execPolicyService;
    private SessionEventCenter eventCenter;
    private TraceRecorder traceRecorder;
    /**
     * 代码诊断收集器。写成 setter 注入而不是构造器参数：构造器已被两个测试手工调用，
     * 加参数等于无谓地改测试。这里允许缺席（{@code required=false}），
     * 缺席时只是没有诊断反馈，不影响任何工具执行。
     */
    private DiagnosticCollector diagnosticCollector;
    private DesktopInteractionService desktopInteractionService;
    @Value("${agent.browser.evaluate-enabled:false}")
    private boolean browserEvaluateEnabled;
    @Value("${agent.permissions.ask-grant-ttl-seconds:300}")
    private long askGrantTtlSeconds = 300L;

    public ToolPipeline(
            ExecutionControl executionControl,
            ToolHookChain toolHookChain,
            SessionPermissionStore permissionStore,
            ActionJournal actionJournal,
            ToolExecutionGuards toolExecutionGuards,
            ToolRegistry toolRegistry,
            ExecPolicyService execPolicyService) {
        this.executionControl = executionControl;
        this.toolHookChain = toolHookChain;
        this.permissionStore = permissionStore;
        this.actionJournal = actionJournal;
        this.toolExecutionGuards = toolExecutionGuards;
        this.toolRegistry = toolRegistry;
        this.execPolicyService = execPolicyService;
    }

    @Autowired(required = false)
    public void setEventCenter(SessionEventCenter eventCenter) {
        this.eventCenter = eventCenter;
    }

    @Autowired(required = false)
    public void setTraceRecorder(TraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
    }

    @Autowired(required = false)
    public void setDiagnosticCollector(DiagnosticCollector diagnosticCollector) {
        this.diagnosticCollector = diagnosticCollector;
    }

    @Autowired(required = false)
    public void setDesktopInteractionService(DesktopInteractionService desktopInteractionService) {
        this.desktopInteractionService = desktopInteractionService;
    }

    /**
     * 一次审批的有效窗口（秒）。
     *
     * <p>审批窗口由管道单独管理，不占用 handler 的执行预算。
     * 工具计时器在审批及暂停检查点通过并拿到资源锁后才启动。</p>
     */
    public long askGrantTtlSeconds() {
        return Math.max(1L, askGrantTtlSeconds);
    }

    /** Bound planner actions use the same argument-aware deadline as ReAct calls. */
    public long timeoutSeconds(String name, String arguments) {
        return toolExecutionGuards.timeoutSeconds(name, arguments);
    }

    public ToolInvocation invoke(ToolRequest request) {
        if (request == null || request.name().isBlank()) {
            return ToolInvocation.policyDenied("", ToolResult.failure(
                    ToolErrorCode.EXECUTION_FAILED, "工具名为空", false));
        }
        RunScope run = request.scope();
        String sid = run.sessionId();
        String name = request.name();
        String args = request.arguments();
        int turn = request.turn();

        if (BROWSER_EVALUATE.equals(name) && !browserEvaluateEnabled) {
            recordNode(sid, turn, AgentStepNode.PERM_DENY,
                    "{\"tool\":\"" + name
                            + "\",\"reason\":\"browser_evaluate_disabled\"}",
                    RunStatus.FAILURE.name());
            return ToolInvocation.policyDenied(name, ToolResult.failure(
                    ToolErrorCode.PERMISSION_DENIED,
                    "browser_evaluate 已被配置禁止",
                    false));
        }
        if (request.allowedTools() != null && !request.allowedTools().contains(name)) {
            recordNode(sid, turn, AgentStepNode.PERM_DENY,
                    "{\"tool\":\"" + name + "\",\"reason\":\"tool_surface\"}",
                    RunStatus.FAILURE.name());
            return ToolInvocation.policyDenied(name, ToolResult.failure(
                    ToolErrorCode.PERMISSION_DENIED,
                    "工具不在本轮允许列表中: " + name,
                    false));
        }
        ExecutionControl.StopReason stop = executionControl.beforeTool(sid);
        if (stop != ExecutionControl.StopReason.NONE) {
            return ToolInvocation.controlStop(name, ToolResult.failure(
                    controlErrorCode(stop), controlMessage(stop), false), stop);
        }
        ExecutionTurnContext.Scope fence = run.execution();
        if (fence != null && !fence.isValid()) {
            return ToolInvocation.fenceRejected(name, ToolResult.failure(
                    ToolErrorCode.CONFLICT, fence.rejectionReason(), false));
        }
        if (request.probeDeny() != null) {
            return ToolInvocation.policyDenied(
                    name, ToolResult.fromLegacy(request.probeDeny()));
        }
        String proposalDeny = run.turnPolicy().denyTool(name);
        if (proposalDeny != null) {
            recordNode(sid, turn, AgentStepNode.HOOK_DENY,
                    "{\"tool\":\"" + name + "\",\"reason\":\"planner_hard_gate\"}",
                    RunStatus.FAILURE.name());
            return ToolInvocation.gateDenied(name, ToolResult.fromLegacy(proposalDeny));
        }
        PermissionMode mode = run.permissionMode();
        boolean planOk = run.planApproved();
        if (mode == PermissionMode.PLAN && !planOk && !PermissionPolicy.isPlanSafe(name)) {
            recordNode(sid, turn, AgentStepNode.PERM_DENY,
                    "{\"tool\":\"" + name + "\",\"mode\":\"PLAN\"}",
                    RunStatus.FAILURE.name());
            return ToolInvocation.policyDenied(name, ToolResult.fromLegacy(
                    "{\"error\":\"Plan 模式未批准，禁止执行: " + name + "\"}"));
        }
        if (AskUserQuestionTool.TOOL_NAME.equals(name)) {
            String text = AskUserQuestionTool.displayText(args);
            emitUserQuestion(sid, AskUserQuestionTool.sseJson(args));
            recordNode(sid, turn, AgentStepNode.WAITING_FOR_HUMAN,
                    "{\"tool\":\"" + name + "\"}", RunStatus.SUCCESS.name());
            return ToolInvocation.userQuestion(
                    name,
                    ToolResult.fromLegacy(MessageConstants.AGENT_USER_QUESTION_TOOL_PENDING),
                    text);
        }
        boolean sub = run.subagent();
        // Resolve hook rewrites before authorization. The digest must cover
        // the arguments that will actually reach the registry, otherwise a
        // rewrite hook could turn an approved invocation into another one.
        ToolPreDecision pre = toolHookChain.before(
                new ToolHookContext(sid, name, args, turn, sub));
        if (pre != null && pre.deny()) {
            recordNode(sid, turn, AgentStepNode.HOOK_DENY,
                    "{\"tool\":\"" + name + "\"}", RunStatus.FAILURE.name());
            String msg = Optional.ofNullable(pre.denyMessage())
                    .orElse("{\"error\":\"工具被 Hook 拒绝\"}");
            return ToolInvocation.policyDenied(name, ToolResult.fromLegacy(msg));
        }
        String effective = (pre != null && pre.argumentsJson() != null)
                ? pre.argumentsJson() : args;
        // Grants are exact invocation approvals. A grant for one command or
        // request must never become a standing approval for the tool name.
        boolean granted = sid != null
                && permissionStore.isAskGranted(sid, name, effective);

        // exec_command 的生效策略：会话覆盖 ?? 全局默认，再计入会话模式（ACCEPT_EDITS 相当于"别问我"）。
        ExecPolicy effectiveExec = execPolicyService.effective(sid, mode);

        // BLOCK 是硬闸门，必须排在 needsSessionGrant 之前：
        // needsSessionGrant 对 BLOCK 返回 false（禁止不该有"批准一下就放行"的路），
        // 所以如果不在这里拦，就变成了放行 —— 这是唯一一处必须前置的判定。
        if (PermissionPolicy.isExecBlocked(name, effectiveExec)) {
            recordNode(sid, turn, AgentStepNode.PERM_DENY,
                    "{\"tool\":\"" + name + "\",\"execPolicy\":\"block\"}",
                    RunStatus.FAILURE.name());
            return ToolInvocation.policyDenied(name, ToolResult.fromLegacy(
                    "{\"error\":\"exec_command 已被策略禁止（exec-policy=block）。"
                            + "本会话或全局把终端命令执行设为禁止，且该档位不受会话模式影响。"
                            + "要执行命令请先把策略切到 ask 或 allow。\"}"));
        }
        // 全自动（自动编辑 + 自动确认）：不产生任何授权入口。留一条 PERM_AUTO 轨迹 ——
        // 否则事后无法区分「这个工具本来就不需要授权」与「本来要授权、被全自动吞掉了」。
        boolean needsGrant = PermissionPolicy.needsSessionGrant(
                mode, name, effectiveExec, run.confirmPolicy());
        if (!needsGrant && !granted && PermissionPolicy.isFullAuto(mode, run.confirmPolicy())
                && PermissionPolicy.isAskDangerous(name)) {
            recordNode(sid, turn, AgentStepNode.PERM_AUTO,
                    "{\"tool\":\"" + name + "\",\"mode\":\"accept_edits\","
                            + "\"confirmPolicy\":\"auto\"}",
                    RunStatus.SUCCESS.name());
        }
        if (needsGrant && !granted) {
            AskGrant grant;
            if ("desktop_act".equals(name)) {
                long observationExpiry = desktopObservationExpiry(run, effective);
                if (observationExpiry <= System.currentTimeMillis()) return staleDesktopObservation(name);
                long ttl = TimeUnit.SECONDS.toMillis(askGrantTtlSeconds());
                long now = System.currentTimeMillis();
                long normalExpiry = now > Long.MAX_VALUE - ttl ? Long.MAX_VALUE : now + ttl;
                grant = permissionStore.issueAskGrantUntil(sid, name, effective,
                        Math.min(normalExpiry, observationExpiry));
                if (grant == null) return staleDesktopObservation(name);
            } else {
                grant = permissionStore.issueAskGrant(sid, name, effective,
                        TimeUnit.SECONDS.toMillis(askGrantTtlSeconds()));
            }
            boolean attached = request.waitForApproval() && grant != null
                    && permissionStore.attachAskResume(sid, grant.token());
            emitPermissionAsk(sid, name, effective, grant == null ? null : grant.token(),
                    grant == null ? 0L : grant.expiresAtEpochMillis());
            recordNode(sid, turn, AgentStepNode.WAITING_FOR_HUMAN,
                    "{\"tool\":\"" + name + "\",\"mode\":\"" + mode.wireName()
                            + "\",\"reason\":\"permission_ask\"}",
                    RunStatus.SUCCESS.name());
            if (!attached) {
                String pending = String.format(
                        MessageConstants.AGENT_PERM_ASK_TOOL_PENDING, name, name);
                return ToolInvocation.permissionAsk(name, ToolResult.fromLegacy(pending));
            }
            try {
                executionControl.markHumanWaitpoint(sid, true);
                var waitStop = executionControl.checkpoint(sid);
                if (waitStop != ExecutionControl.StopReason.NONE) {
                    permissionStore.denyAskToken(sid, grant.token(), name);
                    recordNode(sid, turn, AgentStepNode.PERM_DENY,
                            "{\"tool\":\"" + name + "\",\"reason\":\"control_stop\"}",
                            RunStatus.FAILURE.name());
                    return ToolInvocation.controlStop(name, ToolResult.failure(
                            controlErrorCode(waitStop), controlMessage(waitStop), false), waitStop);
                }
                var approval = permissionStore.awaitAskApproval(sid, grant.token(),
                        Math.max(1L, grant.expiresAtEpochMillis() - System.currentTimeMillis()),
                        () -> executionControl.stopReason(sid) != ExecutionControl.StopReason.NONE);
                if (approval.isEmpty()) {
                    waitStop = executionControl.stopReason(sid);
                    if (waitStop != ExecutionControl.StopReason.NONE) {
                        return ToolInvocation.controlStop(name, ToolResult.failure(
                                controlErrorCode(waitStop), controlMessage(waitStop), false), waitStop);
                    }
                    // 拒绝、超时、取消都会走到这里。必须留一条 PERM_DENY：
                    // 前端那句「用户未在有效期内批准」和真实原因（点了拒绝 / 超时）
                    // 在界面上长得一样，只有轨迹能区分。
                    recordNode(sid, turn, AgentStepNode.PERM_DENY,
                            "{\"tool\":\"" + name + "\",\"reason\":\"approval_not_granted\"}",
                            RunStatus.FAILURE.name());
                    return ToolInvocation.permissionDenied(name, ToolResult.failure(
                            ToolErrorCode.PERMISSION_DENIED, "用户未批准本次工具调用（已拒绝或已超时）", false));
                }
                AskGrant approvedGrant = approval.get();
                granted = approvedGrant.toolName().equals(name)
                        && approvedGrant.argumentsDigest().equals(
                        SessionPermissionStore.argumentDigest(name, effective));
                if (!granted) {
                    recordNode(sid, turn, AgentStepNode.PERM_DENY,
                            "{\"tool\":\"" + name + "\",\"reason\":\"argument_mismatch\"}",
                            RunStatus.FAILURE.name());
                    return ToolInvocation.permissionDenied(name, ToolResult.failure(
                            ToolErrorCode.PERMISSION_DENIED, "批准与当前工具参数不匹配", false));
                }
                recordNode(sid, turn, AgentStepNode.PERM_GRANT,
                        "{\"tool\":\"" + name + "\",\"resumed\":true}",
                        RunStatus.SUCCESS.name());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordNode(sid, turn, AgentStepNode.PERM_DENY,
                        "{\"tool\":\"" + name + "\",\"reason\":\"interrupted\"}",
                        RunStatus.FAILURE.name());
                return ToolInvocation.controlStop(name, ToolResult.failure(
                        ToolErrorCode.CANCELLED, "等待批准时任务已取消", false), ExecutionControl.StopReason.CANCELLED);
            } finally {
                executionControl.markHumanWaitpoint(sid, false);
            }
        }
        ToolInvocation boundaryRejection = executionBoundary(run, name, turn);
        if (boundaryRejection != null) return boundaryRejection;
        ToolInvocation invocation = executeJournaled(
                run,
                sid,
                name,
                effective,
                turn,
                request.runId(),
                request.allowedTools(),
                request.actionBinding(),
                request.budget());
        if (invocation.outcome() != ToolInvocation.Outcome.EXECUTED) return invocation;
        ToolResult raw = invocation.result();
        // A text-only post hook has no authority to turn a failed or unresolved
        // execution into success. Keep the exact structured and legacy terminal
        // result that was persisted to the journal.
        if (!raw.isSuccess()) return invocation;
        String processed = toolHookChain.after(
                new ToolHookContext(sid, name, effective, turn, sub), raw.legacyText());
        ToolResult result = Objects.equals(processed, raw.legacyText())
                ? raw : ToolResult.fromLegacy(processed);
        // 写类工具成功后登记待检查文件，由 AgentLoop 在下次问模型之前统一检查。
        // 不在这里当场编译：那会把每次编辑从毫秒级拖到秒级，而多轮编辑的中间状态
        // 本来就编译不过，检查了也是噪声。
        if (result.isSuccess() && diagnosticCollector != null) {
            Path changed = changedSourcePath(name, effective);
            if (changed != null) {
                diagnosticCollector.registerChanged(changed);
            }
        }
        return ToolInvocation.executed(name, result);
    }

    private long desktopObservationExpiry(RunScope run, String arguments) {
        if (desktopInteractionService == null) return 0L;
        try {
            JsonNode args = ARGS_JSON.readTree(arguments);
            String id = args == null ? "" : args.path("observationId").asText("");
            return desktopInteractionService.observationExpiresAt(run.owner().userId(), run.owner().tenantId(),
                    run.sessionId(), id).map(java.time.Instant::toEpochMilli).orElse(0L);
        } catch (Exception invalid) { return 0L; }
    }

    private static ToolInvocation staleDesktopObservation(String name) {
        return ToolInvocation.policyDenied(name, ToolResult.failure(ToolErrorCode.CONFLICT,
                "STALE_OBSERVATION: 桌面观察已过期、已撤销或不属于当前任务，请重新desktop_observe并为新动作重新申请授权。", false));
    }

    /** Recheck after every blocking boundary, including the resource lock wait. */
    private ToolInvocation executionBoundary(RunScope run, String name, int turn) {
        String sid = run.sessionId();
        ExecutionControl.StopReason stop = executionControl.checkpoint(sid);
        if (stop != ExecutionControl.StopReason.NONE) {
            return ToolInvocation.controlStop(name, ToolResult.failure(
                    controlErrorCode(stop), controlMessage(stop), false), stop);
        }
        ExecutionTurnContext.Scope fence = run.execution();
        if (fence != null && !fence.isValid()) {
            return ToolInvocation.fenceRejected(name, ToolResult.failure(
                    ToolErrorCode.CONFLICT, fence.rejectionReason(), false));
        }
        if (PermissionPolicy.isExecBlocked(name,
                execPolicyService.effective(sid, run.permissionMode()))) {
            recordNode(sid, turn, AgentStepNode.PERM_DENY,
                    "{\"tool\":\"" + name + "\",\"execPolicy\":\"block\","
                            + "\"reason\":\"policy_changed_before_execution\"}",
                    RunStatus.FAILURE.name());
            return ToolInvocation.policyDenied(name, ToolResult.failure(
                    ToolErrorCode.PERMISSION_DENIED,
                    "exec_command 已被策略禁止（exec-policy=block）；等待期间策略已更新，本次调用不再执行。",
                    false));
        }
        return null;
    }

    /**
     * 从工具参数里取「被改动的源文件」的绝对路径。
     *
     * <p>只有 {@code write_file} / {@code edit_file} 走这条路 —— 别的工具不产生需要语法检查的改动。
     * {@code exec_command} 也可能改了文件，但它改了什么无从得知，硬猜会引入错误归因。</p>
     *
     * <p>相对路径的解析必须和工具自身一致，否则诊断会对着一份不存在的路径跑：
     * {@code write_file} 落到 {@code effectiveWorkspaceRoot()}，{@code edit_file} 优先项目根。
     * 这里两个都试一遍，取真实存在的那个；都不存在就按工作区解析（后续会被
     * {@code Files.isRegularFile} 挡掉，不会误报）。</p>
     */
    private static Path changedSourcePath(String toolName, String args) {
        if (!"write_file".equals(toolName) && !"edit_file".equals(toolName)) {
            return null;
        }
        if (args == null || args.isBlank()) {
            return null;
        }
        try {
            JsonNode node = ARGS_JSON.readTree(args);
            JsonNode pathNode = node == null ? null : node.get("path");
            if (pathNode == null || pathNode.isNull()) {
                return null;
            }
            String raw = pathNode.asText("").trim();
            if (raw.isEmpty()) {
                return null;
            }
            Path candidate = Path.of(raw);
            if (candidate.isAbsolute()) {
                return candidate.normalize();
            }
            Path fromWorkspace = BuiltinTools.effectiveWorkspaceRoot()
                    .resolve(raw).normalize();
            Path fromProject = Path.of(System.getProperty("user.dir"))
                    .resolve(raw).normalize();
            if (Files.isRegularFile(fromWorkspace)) {
                return fromWorkspace;
            }
            return Files.isRegularFile(fromProject) ? fromProject : fromWorkspace;
        } catch (Exception e) {
            // 参数解析失败不是错误：拿不到路径就只是没有诊断，工具本身已经执行成功了。
            log.debug("无法从参数中解析改动路径，跳过诊断登记: {}", e.getMessage());
            return null;
        }
    }

    private ToolInvocation executeJournaled(
            RunScope run, String sessionId, String name, String arguments,
            int turn, String runId, Set<String> allowedTools,
            ExecutionTurnContext.ActionBinding actionBinding, ToolInvocationBudget budget) {
        // 必须吃参数：exec_command 的幂等性取决于命令行（只读命令可安全重试，
        // 写类命令重跑可能重复副作用）。按名字取到的永远是注册期那份保守契约。
        ToolDescriptor descriptor = toolExecutionGuards.descriptor(name, arguments);
        ActionJournalKey key = journalKey(run, sessionId, name, arguments, turn, runId,
                actionBinding);
        String argumentsHash = sha256(arguments);
        ExecutionTurnContext.Scope effectScope = run.execution();
        String effectHash = effectScope != null && descriptor.sideEffect() != ToolSideEffect.READ_ONLY
                ? effectArgumentsHash(arguments) : null;
        ActionJournalKey effectKey = effectHash == null ? null : new ActionJournalKey(effectScope.sessionId(),
                sha256(effectScope.executionId()) + ":" + effectScope.planVersion(),
                "effect-" + effectScope.effectNamespace(), sha256(name + "\n" + effectHash));
        int attempt;
        try {
            if (effectKey != null) {
                Optional<ActionJournalEntry> pendingEffect = actionJournal.latest(effectKey);
                if (pendingEffect.isPresent() && (pendingEffect.get().status() == ActionExecutionStatus.RUNNING
                        || pendingEffect.get().status() == ActionExecutionStatus.UNKNOWN)) {
                    return ToolInvocation.executed(name, ToolResult.unknown(
                            "相同副作用已有未确认记录，禁止变更调用编号后自动重试", null));
                }
            }
            // Pre-upgrade DAG receipts have no execution identity. They cannot
            // prove a new task succeeded, but unresolved side effects must still
            // stop a resumed task until explicit human review revises its plan.
            ActionJournalKey legacy = legacyJournalKey(run, name, arguments, turn, actionBinding);
            if (legacy != null) {
                Optional<ActionJournalEntry> old = actionJournal.latest(legacy);
                if (old.isPresent() && (old.get().status() == ActionExecutionStatus.RUNNING
                        || old.get().status() == ActionExecutionStatus.UNKNOWN))
                    return ToolInvocation.executed(name,
                            ToolResult.unknown("旧动作仍有未确认执行记录，需人工核验后再继续", null));
                // The preceding format already included execution identity but
                // used the planner binding directly for the first model call.
                ExecutionTurnContext.ActionBinding original = run.execution().legacyBinding(actionBinding != null
                        ? actionBinding : run.execution().resolve(name + "@" + turn + "@" + sha256(arguments), name));
                ActionJournalKey oldOperation = journalKey(run, sessionId, name, arguments, turn, runId, original);
                if (!oldOperation.equals(key)) {
                    old = actionJournal.latest(oldOperation);
                    if (old.isPresent() && (old.get().status() == ActionExecutionStatus.RUNNING
                            || old.get().status() == ActionExecutionStatus.UNKNOWN))
                        return ToolInvocation.executed(name,
                                ToolResult.unknown("旧操作仍有未确认执行记录，需先核验实际状态", null));
                }
            }
            ActionJournal.Claim claim = actionJournal.claim(key, name, argumentsHash, descriptor.idempotent());
            ActionJournalEntry entry = claim.entry();
            if (!name.equals(entry.toolName()) || !argumentsHash.equals(entry.argumentsHash())) {
                return ToolInvocation.executed(name, ToolResult.failure(ToolErrorCode.CONFLICT,
                        "动作幂等键与原工具或参数不一致", false));
            }
            if (!claim.acquired()) {
                if (entry.status() == ActionExecutionStatus.UNKNOWN
                        || entry.status() == ActionExecutionStatus.RUNNING) {
                    return ToolInvocation.executed(name,
                            ToolResult.unknown("动作已有未确认执行记录，禁止自动重试", null));
                }
                if (entry.status() == ActionExecutionStatus.SUCCEEDED) {
                    // A receipt proves execution, not the original business
                    // result. Never feed an empty success into DAG acceptance.
                    return ToolInvocation.executed(name,
                            ToolResult.unknown("动作已确认执行过，本次已去重；原业务结果不可回放，请只读回验实际结果后再完成验收",
                                    "{\"success\":false,\"status\":\"unknown\",\"errorCode\":\"OUTCOME_UNKNOWN\","
                                            + "\"deduplicated\":true,\"requiresReadback\":true,"
                                            + "\"error\":\"动作已确认执行过，禁止重复执行；原业务结果不可回放，请先只读回验\"}"));
                }
                return ToolInvocation.executed(name, ToolResult.failure(ToolErrorCode.CONFLICT,
                        "动作已有记录且不满足安全重试条件: " + entry.status(), false));
            }
            if (entry.status() != ActionExecutionStatus.RUNNING || entry.attempt() < 1)
                throw new IllegalStateException("ACTION_JOURNAL_INVALID_PERMIT");
            attempt = entry.attempt();
        } catch (Exception e) {
            log.error("Action Journal 预写失败，拒绝工具 {}: {}", name, e.getMessage());
            return ToolInvocation.executed(name, ToolResult.failure(
                    ToolErrorCode.INTERNAL_ERROR, "无法持久化工具执行计划", false));
        }

        EffectPermit effect = null;
        if (effectKey != null) {
            try {
                ActionJournal.Claim permit = actionJournal.claimEffect(effectKey, name, effectHash);
                ActionJournalEntry receipt = permit.entry();
                if (!permit.acquired() || receipt.status() != ActionExecutionStatus.RUNNING
                        || receipt.attempt() < 1 || !name.equals(receipt.toolName())
                        || !effectHash.equals(receipt.argumentsHash())) {
                    ToolResult blocked = ToolResult.unknown(
                            "相同副作用已有未确认记录，变更工具调用编号也不能重放；请先核验实际状态", null);
                    journal(key, name, argumentsHash, ActionExecutionStatus.UNKNOWN, attempt,
                            blocked.errorCode(), blocked.message(), sha256(blocked.legacyText()));
                    return ToolInvocation.executed(name, blocked);
                }
                effect = new EffectPermit(effectKey, effectHash, receipt.attempt());
            } catch (Exception unavailable) {
                // This operation owns a receipt but has no effect permission. It
                // must be closed before returning, and must never reach a handler.
                ToolResult blocked = ToolResult.unknown("无法确认副作用执行许可，禁止自动重试", null);
                try {
                    journal(key, name, argumentsHash, ActionExecutionStatus.UNKNOWN, attempt,
                            blocked.errorCode(), blocked.message(), sha256(blocked.legacyText()));
                } catch (Exception persistenceFailure) {
                    log.error("工具 {} 副作用许可及操作收口失败，保留 RUNNING 记录", name);
                }
                return ToolInvocation.executed(name, blocked);
            }
        }

        ToolResult result;
        ToolResult journalResult;
        ToolInvocation invocation = null;
        boolean interrupted = false;
        try {
            GuardedExecution execution = executeBounded(run, name, arguments, turn, allowedTools,
                    budget == null ? new ToolInvocationBudget(descriptor.timeoutSeconds()) : budget,
                    descriptor);
            invocation = execution.invocation();
            result = invocation.result();
            journalResult = execution.journalResult();
            interrupted = execution.interrupted();
        } catch (ToolLockTimeoutException e) {
            // 没轮到锁 = 工具根本没跑 = 零副作用。必须是可重试的失败，
            // 绝不能落进下面那个 EXECUTION_FAILED（更不能升级成 OUTCOME_UNKNOWN 中止整轮）。
            log.warn("  工具 {} 未拿到锁，退让: {}", name, e.getMessage());
            result = ToolResult.failure(ToolErrorCode.RESOURCE_BUSY, e.getMessage(), true);
            journalResult = result;
        } catch (Exception e) {
            result = ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "工具执行异常: " + Objects.requireNonNullElse(
                            e.getMessage(), e.getClass().getSimpleName()),
                    false);
            journalResult = result;
        }
        try {
            journal(key, name, argumentsHash, terminalStatus(journalResult), attempt,
                    journalResult.errorCode(), journalResult.message(), sha256(journalResult.legacyText()));
            // Never release the stable effect quarantine until its operation
            // outcome committed. A failure in either write leaves it blocking.
            if (effect != null) {
                journal(effect.key(), name, effect.argumentsHash(), terminalStatus(journalResult), effect.attempt(),
                        journalResult.errorCode(), journalResult.message(), sha256(journalResult.legacyText()));
            }
        } catch (Exception e) {
            log.error("工具 {} 已执行但无法写入终态 Journal: {}", name, e.getMessage());
            return ToolInvocation.executed(name,
                    ToolResult.unknown("工具可能已执行，但终态无法持久化；禁止自动重试", null));
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        return invocation != null && invocation.outcome() != ToolInvocation.Outcome.EXECUTED
                ? invocation : ToolInvocation.executed(name, result);
    }

    private record GuardedExecution(ToolInvocation invocation, ToolResult journalResult,
                                    boolean interrupted) { }

    private record EffectPermit(ActionJournalKey key, String argumentsHash, int attempt) { }

    /** Sort every JSON object, retaining array order and the exact tool arguments. */
    private static String effectArgumentsHash(String arguments) {
        try {
            Object value = ARGS_JSON.readerFor(Object.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(arguments);
            return sha256(ARGS_JSON.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value));
        } catch (Exception invalidJson) {
            return sha256(arguments);
        }
    }

    /**
     * Keep locks on the actual handler thread until it really exits. Interrupting
     * an uncooperative handler must still let the caller persist a terminal state;
     * a late handler result is deliberately unable to write to the journal.
     */
    private GuardedExecution executeBounded(RunScope run, String name, String arguments,
                                            int turn, Set<String> allowedTools,
                                            ToolInvocationBudget budget,
                                            ToolDescriptor descriptor) throws Exception {
        Object gate = new Object();
        AtomicBoolean abandoned = new AtomicBoolean();
        AtomicBoolean handlerStarted = new AtomicBoolean();
        FutureTask<ToolInvocation> future = new FutureTask<>(() -> {
            try (var ignored = run.bind(); var tools = ToolCallContext.bind(allowedTools)) {
                return toolExecutionGuards.executeGuarded(name, arguments, run.sessionId(), () -> {
                    ToolInvocation rejection = executionBoundary(run, name, turn);
                    if (rejection != null) return rejection;
                    synchronized (gate) {
                        if (abandoned.get()) return ToolInvocation.controlStop(name,
                                ToolResult.failure(ToolErrorCode.CANCELLED, "工具调用已停止", false),
                                ExecutionControl.StopReason.CANCELLED);
                        handlerStarted.set(true);
                        // Approval, pauses and resource-lock waits have their own bounds.
                        budget.start();
                    }
                    try {
                        return ToolInvocation.executed(name, toolRegistry.executeResult(name, arguments));
                    } finally {
                        budget.finish();
                    }
                });
            }
        });
        Thread worker = Thread.ofVirtual().name("tool-handler-" + name).start(future);
        try {
            while (true) {
                ExecutionControl.StopReason stop = executionControl.stopReason(run.sessionId());
                if (stop != ExecutionControl.StopReason.NONE) {
                    return abandonExecution(name, descriptor, gate, abandoned, handlerStarted, worker,
                            ToolInvocation.controlStop(name, ToolResult.failure(
                                    controlErrorCode(stop), controlMessage(stop), false), stop), false);
                }
                if (budget.expired()) {
                    // Give cooperative handlers a short opportunity to release resources.
                    // The outer AgentLoop grants a larger persistence grace period.
                    try {
                        return expiredExecution(name, descriptor, budget,
                                future.get(100L, TimeUnit.MILLISECONDS));
                    } catch (TimeoutException stillRunning) {
                        return abandonExecution(name, descriptor, gate, abandoned, handlerStarted, worker,
                                ToolInvocation.executed(name, budget.timeoutResult(descriptor)), false);
                    }
                }
                try {
                    ToolInvocation invocation = future.get(50L, TimeUnit.MILLISECONDS);
                    if (budget.expired()) {
                        return expiredExecution(name, descriptor, budget, invocation);
                    }
                    return new GuardedExecution(invocation, invocation.result(), false);
                } catch (TimeoutException waiting) {
                    // Observe stop/deadline without entering a pause checkpoint while a
                    // handler is running. The handler reaches its next safe boundary itself.
                }
            }
        } catch (InterruptedException e) {
            return abandonExecution(name, descriptor, gate, abandoned, handlerStarted, worker,
                    ToolInvocation.controlStop(name,
                            ToolResult.failure(ToolErrorCode.CANCELLED, "工具执行被取消", false),
                            ExecutionControl.StopReason.CANCELLED), true);
        } catch (ExecutionException e) {
            if (budget.expired()) {
                ToolResult timedOut = budget.timeoutResult(descriptor);
                return new GuardedExecution(ToolInvocation.executed(name, timedOut), timedOut, false);
            }
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("工具执行异常", cause);
        } catch (RuntimeException e) {
            return abandonExecution(name, descriptor, gate, abandoned, handlerStarted, worker,
                    ToolInvocation.executed(name, ToolResult.failure(ToolErrorCode.DEPENDENCY_UNAVAILABLE,
                            "无法确认执行控制状态: " + Objects.requireNonNullElse(e.getMessage(), "unknown"), false)), false);
        }
    }

    private static GuardedExecution expiredExecution(String name, ToolDescriptor descriptor,
                                                     ToolInvocationBudget budget, ToolInvocation completed) {
        // The handler may already have supplied precise evidence of an unknown
        // outcome while responding to the deadline's interrupt. Never replace
        // that evidence with a weaker retryable timeout or a late success.
        ToolResult terminal = completed.result().status() == ToolStatus.UNKNOWN
                ? completed.result() : budget.timeoutResult(descriptor);
        return new GuardedExecution(ToolInvocation.executed(name, terminal), terminal, false);
    }

    private static GuardedExecution abandonExecution(String name, ToolDescriptor descriptor,
                                                      Object gate, AtomicBoolean abandoned,
                                                      AtomicBoolean handlerStarted, Thread worker,
                                                      ToolInvocation invocation, boolean interrupted) {
        boolean started;
        synchronized (gate) {
            abandoned.set(true);
            started = handlerStarted.get();
        }
        worker.interrupt();
        ToolResult terminal = invocation.result();
        if (started && descriptor.sideEffect() != ToolSideEffect.READ_ONLY) {
            terminal = ToolResult.unknown("工具已开始执行但尚未确认退出，禁止自动重试"
                    + descriptor.recoveryHint(), null);
            // Preserve the control outcome (so the loop stops), but expose the
            // unresolved result too. A caller must never treat cancellation or
            // deadline as a confirmed failure after a side effect started.
            invocation = new ToolInvocation(invocation.outcome(), name, terminal,
                    invocation.loopSignal());
        }
        return new GuardedExecution(invocation, terminal, interrupted);
    }

    private ActionJournalKey journalKey(
            RunScope run, String sessionId, String name, String arguments,
            int turn, String runId, ExecutionTurnContext.ActionBinding suppliedBinding) {
        ExecutionTurnContext.Scope fence = run == null ? null : run.execution();
        if (fence != null) {
            ExecutionTurnContext.ActionBinding binding = suppliedBinding != null
                    ? suppliedBinding
                    : fence.resolve(name + "@" + turn + "@" + sha256(arguments), name);
            return new ActionJournalKey(
                    fence.sessionId(), sha256(fence.executionId()) + ":" + fence.planVersion(),
                    binding.nodeId(), sha256(binding.idempotencyKey() + "\n" + name + "\n" + sha256(arguments)));
        }
        return new ActionJournalKey(
                sessionId, "run-" + runId, "turn-" + turn + "-" + name,
                sha256(name + "\n" + Objects.requireNonNullElse(arguments, "")));
    }

    private ActionJournalKey legacyJournalKey(RunScope run, String name, String arguments, int turn,
                                              ExecutionTurnContext.ActionBinding suppliedBinding) {
        ExecutionTurnContext.Scope fence = run == null ? null : run.execution();
        if (fence == null) return null;
        ExecutionTurnContext.ActionBinding binding = suppliedBinding != null ? suppliedBinding
                : fence.resolve(name + "@" + turn + "@" + sha256(arguments), name);
        binding = fence.legacyBinding(binding);
        return new ActionJournalKey(fence.sessionId(), fence.planVersion(), binding.nodeId(), binding.idempotencyKey());
    }

    private void journal(
            ActionJournalKey key, String name, String argumentsHash,
            ActionExecutionStatus status, int attempt, ToolErrorCode code,
            String message, String resultDigest) {
        actionJournal.append(new ActionJournalEntry(
                key, name, argumentsHash, status, attempt,
                System.currentTimeMillis(),
                code == null ? "" : code.name(), message, resultDigest));
    }

    private static ActionExecutionStatus terminalStatus(ToolResult result) {
        return switch (result.status()) {
            case SUCCESS -> ActionExecutionStatus.SUCCEEDED;
            case FAILED -> ActionExecutionStatus.FAILED;
            case TIMEOUT -> ActionExecutionStatus.TIMEOUT;
            case CANCELLED -> ActionExecutionStatus.CANCELLED;
            case UNKNOWN -> ActionExecutionStatus.UNKNOWN;
            case AWAITING_USER -> throw new IllegalStateException(
                    "未执行的结果没有终态：工具在等用户批准或回答，尚未运行");
        };
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.requireNonNullElse(value, "")
                            .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static ToolErrorCode controlErrorCode(ExecutionControl.StopReason reason) {
        return reason == ExecutionControl.StopReason.CANCELLED
                ? ToolErrorCode.CANCELLED
                : reason == ExecutionControl.StopReason.DEADLINE_EXCEEDED
                ? ToolErrorCode.TIMEOUT
                : ToolErrorCode.RATE_LIMITED;
    }

    private static String controlMessage(ExecutionControl.StopReason reason) {
        return switch (reason) {
            case CANCELLED -> "任务已取消";
            case DEADLINE_EXCEEDED -> "任务已超过执行 deadline";
            case TOOL_BUDGET_EXCEEDED -> "任务已耗尽工具调用配额";
            case TENANT_QUOTA_EXCEEDED -> "租户 token 配额已耗尽";
            case NONE -> "";
        };
    }

    /**
     * 推送「需要用户批准」事件。
     *
     * <p>带上 {@code display}：exec_command 的审批卡片原本只显示工具名（「终端命令执行」），
     * 用户看不出这条命令要干什么，只能盲批。display 是工具自己给的人话说明
     * （见 {@code ExecCommandParams.displayText}），前端读不到时会退化成现在这样，不影响老客户端。</p>
     */
    private void emitPermissionAsk(String sessionId, String tool, String argumentsJson,
                                   String grantToken, long expiresAt) {
        if (eventCenter == null || StringUtils.isBlank(sessionId)
                || StringUtils.isBlank(tool)) {
            return;
        }
        String display = permissionDisplay(tool, argumentsJson);
        StringBuilder json = new StringBuilder("{\"tool\":\"").append(jsonEscape(tool)).append('"');
        if (!display.isBlank()) {
            json.append(",\"display\":\"").append(jsonEscape(display)).append('"');
        }
        if (StringUtils.isNotBlank(grantToken)) {
            json.append(",\"grantToken\":\"").append(jsonEscape(grantToken)).append('"');
            json.append(",\"expiresAt\":").append(expiresAt);
            json.append(",\"argumentsDigest\":\"")
                    .append(jsonEscape(SessionPermissionStore.argumentDigest(tool, argumentsJson)))
                    .append('"');
        }
        json.append('}');
        eventCenter.publish(sessionId, MessageConstants.SSE_PERMISSION_ASK, json.toString());
    }

    /** Desktop approvals identify one observation/action without copying typed secrets into the card. */
    static String permissionDisplay(String tool, String argumentsJson) {
        if (ToolConcurrencyPolicy.EXEC_TOOL.equals(tool)) {
            return ExecCommandParams.displayText(argumentsJson);
        }
        if (!"desktop_act".equals(tool)) {
            return "";
        }
        try {
            JsonNode args = ARGS_JSON.readTree(argumentsJson);
            if (args == null || !args.isObject()) return "桌面操作（参数无效）";
            String action = args.path("action").asText("");
            StringBuilder display = new StringBuilder("桌面操作 ");
            switch (action) {
                case "click", "double_click", "right_click" -> display.append(action)
                        .append(" · 位置 (").append(args.path("x").asText("?"))
                        .append(", ").append(args.path("y").asText("?")).append(')');
                case "type" -> {
                    String text = args.path("text").asText("");
                    display.append("输入文本 · ").append(text.codePointCount(0, text.length()))
                            .append(" 个字符");
                }
                case "key" -> display.append("按键 · ")
                        .append(StringUtils.abbreviate(args.path("key").asText("?"), 48));
                case "scroll" -> display.append("滚动 · ")
                        .append(args.path("amount").asText("?"))
                        .append(args.has("x") ? " · 位置 (" + args.path("x").asText("?")
                                + ", " + args.path("y").asText("?") + ")" : " · 窗口中心");
                case "focus" -> display.append("切换窗口 · ")
                        .append(StringUtils.abbreviate(args.path("targetWindowId").asText("?"), 48));
                default -> display.append("未知动作");
            }
            String observation = args.path("observationId").asText("");
            if (!observation.isBlank()) {
                display.append(" · 观察 ").append(StringUtils.abbreviate(observation, 96));
            }
            return display.toString();
        } catch (Exception ignored) {
            return "桌面操作（参数无效）";
        }
    }

    /** 最小 JSON 字符串转义：事件体是手拼的，字段值里有引号就会破坏整条事件。 */
    private static String jsonEscape(String raw) {
        return Objects.requireNonNullElse(raw, "")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", " ");
    }

    private void emitUserQuestion(String sessionId, String json) {
        if (eventCenter == null || StringUtils.isBlank(sessionId)
                || StringUtils.isBlank(json)) {
            return;
        }
        eventCenter.publish(sessionId, MessageConstants.SSE_USER_QUESTION, json);
    }

    /**
     * 节点码用 {@link AgentStepNode} 而不是字符串：漏登记的 code 会退化成
     * 「未知 AgentStepNode code」WARN 并被当成非核心节点，轨迹页看不到 —— 线上就踩过
     * （{@code PERM_AUTO} / {@code PERM_GRANT} 一直是字面量，枚举里没有）。
     * 改成枚举参数后，少写一个登记直接编译不过。
     */
    private void recordNode(
            String sessionId, int turn, AgentStepNode node, String content, String status) {
        if (traceRecorder == null) {
            return;
        }
        traceRecorder.recordNode(sessionId, turn, node.name(), content, status, 0);
    }
}
