package com.miniagent.agent.execution;

import com.miniagent.agent.core.ExecutionControl;
import com.miniagent.agent.core.ExecutionTurnContext;
import com.miniagent.agent.core.RunScope;
import com.miniagent.agent.core.SessionEventCenter;
import com.miniagent.agent.hook.ToolHookChain;
import com.miniagent.agent.hook.ToolHookContext;
import com.miniagent.agent.hook.ToolPreDecision;
import com.miniagent.agent.permission.PermissionMode;
import com.miniagent.agent.permission.PermissionPolicy;
import com.miniagent.agent.permission.SessionPermissionStore;
import com.miniagent.agent.tool.AskUserQuestionTool;
import com.miniagent.agent.tool.ToolConcurrencyPolicy;
import com.miniagent.agent.tool.ToolDescriptor;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolRegistry;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.impl.ExecCommandParams;
import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.common.MessageConstants;
import com.miniagent.common.RunStatus;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import com.miniagent.agent.core.ExecutionProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * 工具执行唯一入口。ReAct 循环与规划器绑定动作走同一条管道：
 * 控制面 → fence → 探测限额 → 硬闸门 → 权限 → Hook → Journal → Registry。
 */
@Component
@EnableConfigurationProperties(ExecutionProperties.class)
public class ToolPipeline {

    private static final Logger log = LoggerFactory.getLogger(ToolPipeline.class);

    private final ExecutionControl executionControl;
    private final ToolHookChain toolHookChain;
    private final SessionPermissionStore permissionStore;
    private final ActionJournal actionJournal;
    private final ToolExecutionGuards toolExecutionGuards;
    private final ToolRegistry toolRegistry;
    private final boolean execEnabled;
    private SessionEventCenter eventCenter;
    private TraceRecorder traceRecorder;

    public ToolPipeline(
            ExecutionControl executionControl,
            ToolHookChain toolHookChain,
            SessionPermissionStore permissionStore,
            ActionJournal actionJournal,
            ToolExecutionGuards toolExecutionGuards,
            ToolRegistry toolRegistry,
            @Value("${agent.tools.exec-enabled:true}") boolean execEnabled) {
        this.executionControl = executionControl;
        this.toolHookChain = toolHookChain;
        this.permissionStore = permissionStore;
        this.actionJournal = actionJournal;
        this.toolExecutionGuards = toolExecutionGuards;
        this.toolRegistry = toolRegistry;
        this.execEnabled = execEnabled;
    }

    @Autowired(required = false)
    public void setEventCenter(SessionEventCenter eventCenter) {
        this.eventCenter = eventCenter;
    }

    @Autowired(required = false)
    public void setTraceRecorder(TraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
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

        ExecutionControl.StopReason stop = executionControl.beforeTool(sid);
        if (stop != ExecutionControl.StopReason.NONE) {
            return ToolInvocation.controlStop(name, ToolResult.failure(
                    controlErrorCode(stop), controlMessage(stop), false));
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
            recordNode(sid, turn, "HOOK_DENY",
                    "{\"tool\":\"" + name + "\",\"reason\":\"planner_hard_gate\"}",
                    RunStatus.FAILURE.name());
            return ToolInvocation.gateDenied(name, ToolResult.fromLegacy(proposalDeny));
        }
        PermissionMode mode = run.permissionMode();
        boolean planOk = run.planApproved();
        if (mode == PermissionMode.PLAN && !planOk && !PermissionPolicy.isPlanSafe(name)) {
            recordNode(sid, turn, "PERM_DENY",
                    "{\"tool\":\"" + name + "\",\"mode\":\"PLAN\"}",
                    RunStatus.FAILURE.name());
            return ToolInvocation.policyDenied(name, ToolResult.fromLegacy(
                    "{\"error\":\"Plan 模式未批准，禁止执行: " + name + "\"}"));
        }
        if (AskUserQuestionTool.TOOL_NAME.equals(name)) {
            String text = AskUserQuestionTool.displayText(args);
            emitUserQuestion(sid, AskUserQuestionTool.sseJson(args));
            recordNode(sid, turn, "WAITING_FOR_HUMAN",
                    "{\"tool\":\"" + name + "\"}", RunStatus.SUCCESS.name());
            return ToolInvocation.userQuestion(
                    name,
                    ToolResult.fromLegacy(MessageConstants.AGENT_USER_QUESTION_TOOL_PENDING),
                    text);
        }
        boolean granted = sid != null && permissionStore.isAskGranted(sid, name);
        if (PermissionPolicy.needsSessionGrant(mode, name, execEnabled) && !granted) {
            emitPermissionAsk(sid, name, args);
            recordNode(sid, turn, "WAITING_FOR_HUMAN",
                    "{\"tool\":\"" + name + "\",\"mode\":\"" + mode.wireName()
                            + "\",\"reason\":\"permission_ask\"}",
                    RunStatus.SUCCESS.name());
            String pending = String.format(
                    MessageConstants.AGENT_PERM_ASK_TOOL_PENDING, name, name);
            return ToolInvocation.permissionAsk(name, ToolResult.fromLegacy(pending));
        }
        boolean sub = run.subagent();
        ToolPreDecision pre = toolHookChain.before(
                new ToolHookContext(sid, name, args, turn, sub));
        if (pre != null && pre.deny()) {
            recordNode(sid, turn, "HOOK_DENY",
                    "{\"tool\":\"" + name + "\"}", RunStatus.FAILURE.name());
            String msg = Optional.ofNullable(pre.denyMessage())
                    .orElse("{\"error\":\"工具被 Hook 拒绝\"}");
            return ToolInvocation.policyDenied(name, ToolResult.fromLegacy(msg));
        }
        String effective = (pre != null && pre.argumentsJson() != null)
                ? pre.argumentsJson() : args;
        ToolResult raw = executeJournaled(run, sid, name, effective, turn, request.runId());
        String processed = toolHookChain.after(
                new ToolHookContext(sid, name, effective, turn, sub), raw.legacyText());
        ToolResult result = Objects.equals(processed, raw.legacyText())
                ? raw : ToolResult.fromLegacy(processed);
        return ToolInvocation.executed(name, result);
    }

    private ToolResult executeJournaled(
            RunScope run, String sessionId, String name, String arguments,
            int turn, String runId) {
        // 必须吃参数：exec_command 的幂等性取决于命令行（只读命令可安全重试，
        // 写类命令重跑可能重复副作用）。按名字取到的永远是注册期那份保守契约。
        ToolDescriptor descriptor = toolExecutionGuards.descriptor(name, arguments);
        ActionJournalKey key = journalKey(run, sessionId, name, arguments, turn, runId);
        String argumentsHash = sha256(arguments);
        int attempt = 1;
        try {
            Optional<ActionJournalEntry> previous = actionJournal.latest(key);
            if (previous.isPresent()) {
                ActionJournalEntry entry = previous.get();
                if (entry.status() == ActionExecutionStatus.UNKNOWN
                        || entry.status() == ActionExecutionStatus.RUNNING) {
                    return ToolResult.unknown("动作已有未确认执行记录，禁止自动重试", null);
                }
                if (entry.status() == ActionExecutionStatus.SUCCEEDED) {
                    return ToolResult.success("{\"success\":true,\"deduplicated\":true}");
                }
                if (!descriptor.idempotent() || !entry.status().terminal()) {
                    return ToolResult.failure(ToolErrorCode.CONFLICT,
                            "动作已有终态记录且不满足安全重试条件: " + entry.status(),
                            false);
                }
                attempt = entry.attempt() + 1;
                journal(key, name, argumentsHash, ActionExecutionStatus.READY, attempt,
                        ToolErrorCode.NONE, "幂等动作恢复重试", "");
            } else {
                journal(key, name, argumentsHash, ActionExecutionStatus.PLANNED, 0,
                        ToolErrorCode.NONE, "", "");
                journal(key, name, argumentsHash, ActionExecutionStatus.READY, attempt,
                        ToolErrorCode.NONE, "", "");
            }
            journal(key, name, argumentsHash, ActionExecutionStatus.RUNNING, attempt,
                    ToolErrorCode.NONE, "", "");
        } catch (Exception e) {
            log.error("Action Journal 预写失败，拒绝工具 {}: {}", name, e.getMessage());
            return ToolResult.failure(
                    ToolErrorCode.INTERNAL_ERROR, "无法持久化工具执行计划", false);
        }

        ToolResult result;
        try {
            result = toolExecutionGuards.executeGuarded(name, arguments, sessionId,
                    () -> toolRegistry.executeResult(name, arguments));
        } catch (ToolLockTimeoutException e) {
            // 没轮到锁 = 工具根本没跑 = 零副作用。必须是可重试的失败，
            // 绝不能落进下面那个 EXECUTION_FAILED（更不能升级成 OUTCOME_UNKNOWN 中止整轮）。
            log.warn("  工具 {} 未拿到锁，退让: {}", name, e.getMessage());
            result = ToolResult.failure(ToolErrorCode.RESOURCE_BUSY, e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = ToolResult.failure(ToolErrorCode.CANCELLED, "工具执行被取消", false);
        } catch (Exception e) {
            result = ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "工具执行异常: " + Objects.requireNonNullElse(
                            e.getMessage(), e.getClass().getSimpleName()),
                    false);
        }
        try {
            journal(key, name, argumentsHash, terminalStatus(result), attempt,
                    result.errorCode(), result.message(), sha256(result.legacyText()));
        } catch (Exception e) {
            log.error("工具 {} 已执行但无法写入终态 Journal: {}", name, e.getMessage());
            return ToolResult.unknown("工具可能已执行，但终态无法持久化；禁止自动重试", null);
        }
        return result;
    }

    private ActionJournalKey journalKey(
            RunScope run, String sessionId, String name, String arguments,
            int turn, String runId) {
        ExecutionTurnContext.Scope fence = run == null ? null : run.execution();
        if (fence != null) {
            ExecutionTurnContext.ActionBinding binding = fence.resolve(
                    name + "@" + turn + "@" + sha256(arguments), name);
            return new ActionJournalKey(
                    fence.sessionId(), fence.planVersion(),
                    binding.nodeId(), binding.idempotencyKey());
        }
        return new ActionJournalKey(
                sessionId, "run-" + runId, "turn-" + turn + "-" + name,
                sha256(name + "\n" + Objects.requireNonNullElse(arguments, "")));
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
            case TOKEN_BUDGET_EXCEEDED -> "任务已耗尽 token 预算";
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
    private void emitPermissionAsk(String sessionId, String tool, String argumentsJson) {
        if (eventCenter == null || StringUtils.isBlank(sessionId)
                || StringUtils.isBlank(tool)) {
            return;
        }
        String display = ToolConcurrencyPolicy.EXEC_TOOL.equals(tool)
                ? ExecCommandParams.displayText(argumentsJson)
                : "";
        StringBuilder json = new StringBuilder("{\"tool\":\"").append(jsonEscape(tool)).append('"');
        if (!display.isBlank()) {
            json.append(",\"display\":\"").append(jsonEscape(display)).append('"');
        }
        json.append('}');
        eventCenter.publish(sessionId, MessageConstants.SSE_PERMISSION_ASK, json.toString());
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

    private void recordNode(
            String sessionId, int turn, String node, String content, String status) {
        if (traceRecorder == null) {
            return;
        }
        traceRecorder.recordNode(sessionId, turn, node, content, status, 0);
    }
}
