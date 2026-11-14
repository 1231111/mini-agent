package com.miniagent.agent.planner;

import com.miniagent.agent.tool.ToolStatus;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit human reconciliation. Never exposed as a model tool and never replays an uncertain action. */
@Component
public class UnknownOutcomeReconciler {
    private static final Logger LOG = LoggerFactory.getLogger(UnknownOutcomeReconciler.class);
    public enum Decision { CONFIRMED_APPLIED, CONFIRMED_NOT_APPLIED }
    public record Request(String executionId, long version, long planVersion, String nodeId,
                          Decision decision, String evidence, boolean confirmed) { }

    private final PlannerStateStore states;
    private final StepEvaluator evaluator;
    private final TodoStateProjector projector;

    public UnknownOutcomeReconciler(PlannerStateStore states, StepEvaluator evaluator,
                                   TodoStateProjector projector) {
        this.states = states;
        this.evaluator = evaluator;
        this.projector = projector;
    }

    public StateSnapshot reconcile(String sessionId, Long reviewerId, Request request) {
        return reconcile(sessionId, reviewerId, request, () -> { });
    }

    public StateSnapshot reconcile(String sessionId, Long reviewerId, Request request, Runnable beforeCommit) {
        if (reviewerId == null || request == null || !request.confirmed()
                || request.decision() == null || request.evidence() == null
                || request.evidence().isBlank() || request.evidence().length() > 8000) {
            throw new IllegalArgumentException("请核对实际页面或业务回执，填写不超过8000字的核验依据，并明确确认处理。");
        }
        StateSnapshot current = states.get(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("当前任务没有可核验的执行图。"));
        if (alreadyReviewed(current, reviewerId, request)) return current;
        if (current.version() != request.version() || current.planVersion() != request.planVersion()
                || !java.util.Objects.equals(current.executionId(), request.executionId())) {
            throw new PlannerStateStore.VersionConflictException(request.version(), current.version());
        }
        if (current.graph().hasRunning()) throw new IllegalStateException("请等待任务完全停止后再核验。");
        TaskNode node = current.graph().byId(request.nodeId());
        if (node == null || (node.status() != TaskNodeStatus.OUTCOME_UNKNOWN
                && node.toolStatus() != ToolStatus.UNKNOWN)) {
            throw new IllegalArgumentException("该节点当前不是结果待核验状态，请刷新任务状态。");
        }
        String evidence = request.evidence().trim();
        TaskNode resolved;
        if (request.decision() == Decision.CONFIRMED_APPLIED) {
            // Human testimony does not override the node's frozen acceptance contract.
            NodeOutputBinder.Bound bound = NodeOutputBinder.bind(node, evidence, List.of(), "");
            StepEvaluator.EvalResult accepted = evaluator.evaluateAfterLoop(node, bound.evidence());
            if (!accepted.ok() || !bound.complete(node)) {
                throw new IllegalArgumentException("核验依据尚不满足原步骤验收条件："
                        + (accepted.ok() ? "缺少必需的输出" : accepted.reason()));
            }
            resolved = node.withStatus(TaskNodeStatus.SUCCESS).withToolStatus(ToolStatus.SUCCESS)
                    .withOutput(bound.evidence(), bound.bindings()).withError("");
        } else {
            // Explicitly authorized new attempt uses a NEW plan version. Keep the original
            // UNKNOWN journal receipt immutable; ordinary 'continue' cannot unlock it.
            resolved = node.withStatus(TaskNodeStatus.PENDING).withToolStatus(null)
                    .withOutput("", Map.of()).withError("").withRetryInc();
        }
        Map<String, Object> metadata = new LinkedHashMap<>(current.execution());
        List<Object> decisions = new ArrayList<>();
        Object existing = metadata.get("outcomeReconciliations");
        if (existing instanceof List<?> history) decisions.addAll(history);
        if (decisions.size() >= 100) throw new IllegalStateException("核验记录已达上限，请创建新的任务。");
        decisions.add(Map.of("reviewerId", reviewerId, "nodeId", node.id(),
                "decision", request.decision().name(), "evidence", evidence,
                "previousVersion", current.version(), "previousPlanVersion", current.planVersion(),
                "at", Instant.now().toString(), "mode", "HUMAN_REVIEW"));
        metadata.put("outcomeReconciliations", List.copyOf(decisions));
        StateSnapshot next = current.withGraph(current.graph().replace(resolved)).withExecution(metadata);
        if (request.decision() == Decision.CONFIRMED_NOT_APPLIED) {
            next = next.revisePlan(next.graph(), next.goal(), "human_verified_not_applied");
        }
        beforeCommit.run();
        StateSnapshot committed = states.commit(sessionId, request.version(), next);
        try { projector.project(sessionId, committed.graph()); }
        catch (RuntimeException projectionFailure) {
            // The persisted graph is authoritative. A UI cache failure must not
            // turn an accepted decision into a rejected/retryable HTTP operation.
            LOG.warn("Outcome review committed; progress projection requires refresh session={} version={}",
                    sessionId, committed.version(), projectionFailure);
        }
        return committed;
    }

    private static boolean alreadyReviewed(StateSnapshot current, Long reviewerId, Request request) {
        if (!java.util.Objects.equals(current.executionId(), request.executionId())) return false;
        Object existing = current.execution().get("outcomeReconciliations");
        if (!(existing instanceof List<?> history)) return false;
        for (Object entry : history) {
            if (!(entry instanceof Map<?, ?> item)) continue;
            if (item.get("reviewerId") instanceof Number actor && actor.longValue() == reviewerId
                    && item.get("previousVersion") instanceof Number version && version.longValue() == request.version()
                    && item.get("previousPlanVersion") instanceof Number plan && plan.longValue() == request.planVersion()
                    && java.util.Objects.equals(item.get("nodeId"), request.nodeId())
                    && java.util.Objects.equals(item.get("decision"), request.decision().name())
                    && java.util.Objects.equals(item.get("evidence"), request.evidence().trim())) return true;
        }
        return false;
    }
}
