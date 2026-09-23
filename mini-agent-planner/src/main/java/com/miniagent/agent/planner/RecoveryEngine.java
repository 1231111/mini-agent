package com.miniagent.agent.planner;

import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.trace.AgentStepNode;
import com.miniagent.agent.trace.TraceRecorder;
import com.miniagent.common.RunStatus;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 失败诊断四类 + 按类熔断。
 */
@Component
public class RecoveryEngine {

    private static final Logger log = LoggerFactory.getLogger(RecoveryEngine.class);
    private static final String RECOVERY_KEY_PREFIX = "recovery.";

    private final PlannerStateStore stateStore;
    private final CapabilityRegistry capabilityRegistry;
    private final PlannerProperties properties;
    private TraceRecorder traceRecorder;

    public RecoveryEngine(PlannerStateStore stateStore,
                          CapabilityRegistry capabilityRegistry,
                          PlannerProperties properties) {
        this.stateStore = stateStore;
        this.capabilityRegistry = capabilityRegistry;
        this.properties = properties;
    }

    public void setTraceRecorder(TraceRecorder traceRecorder) {
        this.traceRecorder = traceRecorder;
    }

    public FailureDiagnosis diagnose(TaskNode node, String tool, String error) {
        return diagnose(node, tool, error, ToolErrorCode.NONE);
    }

    public FailureDiagnosis diagnose(TaskNode node, String tool, String error,
                                     ToolErrorCode errorCode) {
        FailureKind kind = classify(errorCode, error);
        ActionRetryPolicy policy = ActionRetryPolicy.forCapability(
                node == null ? "" : node.capability());
        FailureClass fc = mapClass(kind, node == null ? 0 : node.retryCount(),
                policy, errorCode);
        String fix = switch (fc) {
            case LOCAL_REPAIR -> "修正参数/纠偏后重试同一工具";
            case REPLACE_TOOL -> "更换同类能力工具";
            case REWRITE_GRAPH -> "重写任务子图";
            case REVISE_GOAL -> "修订 Goal 约束";
        };
        return new FailureDiagnosis(fc, kind, node == null ? "" : node.id(), tool, error, fix);
    }

    static FailureKind classify(ToolErrorCode code, String error) {
        if (code != null && code != ToolErrorCode.NONE) {
            return switch (code) {
                case INVALID_ARGUMENT -> FailureKind.PARAM_ERROR;
                case UNKNOWN_TOOL -> FailureKind.UNKNOWN_TOOL;
                case PERMISSION_DENIED -> FailureKind.PERMISSION_DENIED;
                case TIMEOUT -> FailureKind.TIMEOUT;
                case RATE_LIMITED, DEPENDENCY_UNAVAILABLE, RESOURCE_BUSY -> FailureKind.RESOURCE_EXHAUSTED;
                case CONFLICT -> FailureKind.CONFLICT;
                case NOT_FOUND, EXECUTION_FAILED, INTERNAL_ERROR -> FailureKind.TOOL_ERROR;
                case CANCELLED -> FailureKind.GENERIC;
                case EMPTY_RESULT, OUTCOME_UNKNOWN -> FailureKind.EVAL_FAILED;
                case NONE -> classifyKind(error);
            };
        }
        return classifyKind(error);
    }

    static FailureKind classifyKind(String error) {
        String err = error == null ? "" : error.toLowerCase(Locale.ROOT);
        if (err.startsWith("drift:") || err.contains("drift:")) {
            return FailureKind.DRIFT;
        }
        if (err.contains("硬闸门") || err.contains("hard_gate") || err.contains("hard gate"))
            return FailureKind.HARD_GATE;
        if (err.contains("orphan_running")) {
            return FailureKind.ORPHAN_RUNNING;
        }
        if (err.contains("未知工具") || err.contains("unknown tool") || err.contains("not found")
                || err.contains("unsupported") || err.contains("不可用") || err.contains("unavailable"))
            return FailureKind.UNKNOWN_TOOL;
        if (err.contains("参数") || err.contains("argument") || err.contains("missing")
                || err.contains("required") || (err.contains("json") && err.contains("parse")))
            return FailureKind.PARAM_ERROR;
        if (err.contains("evidence") || err.contains("语义验收") || err.contains("file_exists")
                || err.contains("验收") || err.contains("eval"))
            return FailureKind.EVAL_FAILED;
        if (err.contains("no ready") || err.contains("无 ready")) {
            return FailureKind.NO_READY;
        }
        if (err.contains("目标") || err.contains("goal") || err.contains("无法完成")
                || err.contains("contradict"))
            return FailureKind.GOAL_BLOCKED;
        if (err.contains("\"error\"") || err.contains("timeout") || err.contains("工具执行"))
            return FailureKind.TOOL_ERROR;
        return FailureKind.GENERIC;
    }

    static FailureClass mapClass(FailureKind kind, int retryCount) {
        return mapClass(kind, retryCount, ActionRetryPolicy.none(), ToolErrorCode.NONE);
    }

    static FailureClass mapClass(FailureKind kind, int retryCount,
                                 ActionRetryPolicy policy, ToolErrorCode code) {
        if (policy != null && policy.retryable(code) && policy.withinLocalAttempts(retryCount)) {
            return FailureClass.LOCAL_REPAIR;
        }
        return switch (kind) {
            case PARAM_ERROR, HARD_GATE -> FailureClass.LOCAL_REPAIR;
            case UNKNOWN_TOOL -> FailureClass.REPLACE_TOOL;
            case DRIFT -> retryCount >= 1 ? FailureClass.REWRITE_GRAPH : FailureClass.LOCAL_REPAIR;
            case EVAL_FAILED, TOOL_ERROR, GENERIC, ORPHAN_RUNNING -> {
                if (retryCount >= 2) {
                    yield FailureClass.REWRITE_GRAPH;
                }
                if (retryCount >= 1) {
                    yield FailureClass.REPLACE_TOOL;
                }
                yield FailureClass.LOCAL_REPAIR;
            }
            case NO_READY -> FailureClass.REWRITE_GRAPH;
            case GOAL_BLOCKED, PERMISSION_DENIED -> FailureClass.REVISE_GOAL;
            case TIMEOUT, RESOURCE_EXHAUSTED, CONFLICT, DEPENDENCY_FAILED -> {
                if (retryCount >= 1) {
                    yield FailureClass.REWRITE_GRAPH;
                }
                yield FailureClass.LOCAL_REPAIR;
            }
        };
    }

    public int classLimit(FailureClass fc) {
        return switch (fc) {
            case LOCAL_REPAIR -> properties.getMaxLocalRepair();
            case REPLACE_TOOL -> properties.getMaxReplaceTool();
            case REWRITE_GRAPH -> properties.getMaxRewriteGraph();
            case REVISE_GOAL -> properties.getMaxReviseGoal();
        };
    }

    public int classCount(StateSnapshot snap, FailureClass fc) {
        if (snap == null || snap.execution() == null) {
            return 0;
        }
        Object v = snap.execution().get(RECOVERY_KEY_PREFIX + fc.name());
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? 0 : Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 应用恢复并 CAS 升版本。总上限或分类熔断时返回 empty。
     */
    public Optional<StateSnapshot> recover(String sessionId, FailureDiagnosis dx) {
        StateSnapshot cur = stateStore.get(sessionId).orElse(null);
        if (cur == null) {
            return Optional.empty();
        }
        if (cur.recoveryCount() >= properties.getMaxRecoveries()) {
            log.warn("Recovery 总上限 session={} count={}", sessionId, cur.recoveryCount());
            return Optional.empty();
        }
        int used = classCount(cur, dx.failureClass());
        int limit = classLimit(dx.failureClass());
        if (used >= limit) {
            log.warn("Recovery 分类熔断 session={} class={} used={}/{}",
                    sessionId, dx.failureClass(), used, limit);
            return Optional.empty();
        }
        TaskNode node = cur.graph().byId(dx.taskId());
        if (node == null) {
            return Optional.empty();
        }

        TaskNode recovering = node.withStatus(TaskNodeStatus.RECOVERING).withError(dx.reason());
        TaskGraph working = cur.graph().replace(recovering);

        TaskGraph nextGraph = switch (dx.failureClass()) {
            case LOCAL_REPAIR -> working.replace(
                    recovering.withStatus(TaskNodeStatus.PENDING).withRetryInc());
            case REPLACE_TOOL -> {
                TaskNode next = recovering.withStatus(TaskNodeStatus.PENDING)
                        .withRetryInc();
                if (capabilityRegistry.containsTool(dx.tool())) {
                    next = next.withBlockedTool(dx.tool());
                }
                yield working.replace(next);
            }
            case REWRITE_GRAPH -> working.replace(
                    recovering.withStatus(TaskNodeStatus.PENDING).withRetryInc());
            case REVISE_GOAL -> working.replace(
                    recovering.withStatus(TaskNodeStatus.PENDING).withRetryInc());
        };
        // REWRITE_GRAPH 的子图重写只走 PlanningLoop.tryLlmReplan；
        // 这里不再拆「准备/执行」过程节点。

        Goal nextGoal = cur.goal();
        if (dx.failureClass() == FailureClass.REVISE_GOAL && nextGoal != null) {
            List<String> cons = new ArrayList<>(nextGoal.constraints());
            cons.add("recovery: " + abbreviate(dx.reason(), 80));
            nextGoal = new Goal(nextGoal.goalId(), nextGoal.objective(), nextGoal.signals(),
                    nextGoal.taskType(), nextGoal.entities(), cons, nextGoal.successCriteria());
        }

        Map<String, Object> exec = new HashMap<>(cur.execution());
        exec.put(RECOVERY_KEY_PREFIX + dx.failureClass().name(), used + 1);
        exec.put("lastFailureKind", dx.kind().name());

        // Recovery 可能替换工具、重写节点或修订目标，必须让旧 dispatch fence 失效。
        StateSnapshot patched = cur.revisePlan(nextGraph, nextGoal,
                "recovery_" + dx.failureClass().name().toLowerCase())
                .withExecution(exec).withRecoveryInc();
        try {
            StateSnapshot committed = stateStore.commit(sessionId, cur.version(), patched);
            stateStore.appendEvent(sessionId, new DomainEvent(
                    "ev_" + UUID.randomUUID().toString().substring(0, 8),
                    DomainEventType.RECOVERY_APPLIED, null, dx.taskId(),
                    Map.of("class", dx.failureClass().name(), "kind", dx.kind().name(),
                            "tool", dx.tool(), "classCount", used + 1),
                    null));
            traceRecovery(sessionId, dx);
            if (traceRecorder != null) {
                traceRecorder.recordNode(sessionId, 0, AgentStepNode.STATE_COMMIT.name(),
                        "{\"version\":" + committed.version() + ",\"recovery\":true}",
                        RunStatus.SUCCESS.name(), 0);
            }
            return Optional.of(committed);
        } catch (PlannerStateStore.VersionConflictException e) {
            stateStore.appendEvent(sessionId, new DomainEvent(
                    "ev_" + UUID.randomUUID().toString().substring(0, 8),
                    DomainEventType.VERSION_CONFLICT, null, dx.taskId(),
                    Map.of("expected", e.expected(), "actual", e.actual()), null));
            return Optional.empty();
        }
    }

    private void traceRecovery(String sessionId, FailureDiagnosis dx) {
        if (traceRecorder == null) {
            return;
        }
        String node = switch (dx.failureClass()) {
            case LOCAL_REPAIR -> AgentStepNode.RECOVERY_LOCAL.name();
            case REPLACE_TOOL -> AgentStepNode.RECOVERY_REPLACE_TOOL.name();
            case REWRITE_GRAPH -> AgentStepNode.RECOVERY_REWRITE_GRAPH.name();
            case REVISE_GOAL -> AgentStepNode.RECOVERY_REVISE_GOAL.name();
        };
        traceRecorder.recordNode(sessionId, 0, node,
                "{\"taskId\":\"" + dx.taskId() + "\",\"kind\":\"" + dx.kind()
                        + "\",\"tool\":\"" + dx.tool()
                        + "\",\"reason\":" + quote(dx.reason()) + "}",
                RunStatus.SUCCESS.name(), 0);
    }

    private static String quote(String s) {
        if (s == null) {
            return "\"\"";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
