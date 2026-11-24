package com.miniagent.agent.planner;

import com.miniagent.agent.scope.TaskScopeRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Planner 单一事实源：session 绑定 + version CAS。
 * 有 {@link PlannerStatePersistence} 时走共享存储（DB/Redis，可水平扩容）；
 * 无持久化时回退进程内 map（单测）。
 *
 * <h2>为什么内部 key 是「任务作用域键」而不是 sessionId</h2>
 *
 * <p>规划图属于<b>某一次交付</b>，不属于整个会话。挂在 sessionId 上就会出现
 * 「上一轮那张没做完的图，被这一轮的新问题读到并接管」（实测就是这条通路）。
 * 因此这里所有读写都先把 sessionId 翻成 {@link TaskScopeRegistry#scopeKey(String)}
 * 再落到存储层。对调用方（{@code PlanningLoop} 的 40 处调用）完全透明 ——
 * 它们继续传 sessionId，而这个类自己保证「同一任务内稳定、换任务后不串台」。</p>
 *
 * <p>切换点是 {@code ContextLoader} 每轮判定的任务边界，见 {@link TaskScopeRegistry}。
 * 这样做的价值是：<b>不需要任何模块记得在换任务时清状态</b> ——
 * 旧状态的 key 已经不再被任何人查。</p>
 */
@Component
public class PlannerStateStore {

    /** 计划审批状态存放在 StateSnapshot.execution 的这个键下。 */
    public static final String EXECUTION_PLAN_APPROVAL = "planApproval";
    public static final String PLAN_APPROVAL_PENDING = "pending";
    public static final String PLAN_APPROVAL_APPROVED = "approved";
    public static final String EXECUTION_NODE_TOOL_BUDGETS = "nodeToolBudgets";

    public record NodeToolCallReservation(boolean reserved, int callsUsed, String reason) {
        public NodeToolCallReservation {
            callsUsed = Math.max(0, callsUsed);
            reason = reason == null ? "" : reason;
        }
    }

    public static final class VersionConflictException extends RuntimeException {
        private final long expected;
        private final long actual;

        public VersionConflictException(long expected, long actual) {
            super("state version conflict expected=" + expected + " actual=" + actual);
            this.expected = expected;
            this.actual = actual;
        }

        public long expected() { return expected; }
        public long actual() { return actual; }
    }

    private final ConcurrentHashMap<String, StateSnapshot> memory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<DomainEvent>> memoryEvents = new ConcurrentHashMap<>();
    private final PlannerStatePersistence persistence;
    /** 任务作用域解析器；单测直接 new 时为 null，此时 key 退化为 sessionId（等价 taskId=0）。 */
    private final TaskScopeRegistry scopeRegistry;

    /** 单测：纯内存 */
    public PlannerStateStore() {
        this.persistence = null;
        this.scopeRegistry = null;
    }

    public PlannerStateStore(TaskScopeRegistry scopeRegistry) {
        this.persistence = null;
        this.scopeRegistry = scopeRegistry;
    }

    @Autowired
    public PlannerStateStore(@Autowired(required = false) PlannerStatePersistence persistence,
                             @Autowired(required = false) TaskScopeRegistry scopeRegistry) {
        this.persistence = persistence;
        this.scopeRegistry = scopeRegistry;
    }

    /**
     * sessionId → 任务作用域键。
     *
     * <p>唯一的翻译点。{@code taskId == 0} 时与原 sessionId 逐字节相同，
     * 因此历史数据不用迁移。</p>
     */
    private String key(String sessionId) {
        return scopeRegistry == null ? sessionId : scopeRegistry.scopeKey(sessionId);
    }

    public Optional<StateSnapshot> get(String sessionId) {
        String k = key(sessionId);
        if (k == null) {
            return Optional.empty();
        }
        if (persistence != null)
            return persistence.load(k).map(PlannerStatePersistence.Bundle::snapshot);
        return Optional.ofNullable(memory.get(k));
    }

    /** Read only the authoritative state needed by dispatch; audit events do not grant execution. */
    public boolean dispatchFenceValid(String sessionId, ActionProposal proposal) {
        String k = key(sessionId);
        if (k == null || proposal == null || proposal.actions().isEmpty()) return false;
        return persistence != null ? persistence.dispatchFenceValid(k, proposal)
                : PlannerDispatchFence.matches(memory.get(k), k, proposal);
    }

    /**
     * Durably reserves one tool invocation for a logical DAG node. The counter is
     * keyed by plan version and node id, so continuation chunks and explicit user
     * resumes cannot reset the node circuit breaker. A revised graph gets a fresh
     * budget because its plan version changes.
     */
    public NodeToolCallReservation reserveNodeToolCall(String sessionId, String executionId,
                                                        long planVersion, String nodeId,
                                                        int maximumCalls) {
        if (sessionId == null || sessionId.isBlank() || nodeId == null || nodeId.isBlank()) {
            return new NodeToolCallReservation(false, 0, "节点预算缺少任务或节点标识");
        }
        int limit = Math.max(1, Math.min(120, maximumCalls));
        String budgetKey = planVersion + ":" + nodeId;
        for (int attempt = 0; attempt < 8; attempt++) {
            StateSnapshot current = get(sessionId).orElse(null);
            if (current == null || current.planVersion() != planVersion
                    || !Objects.equals(current.executionId(), executionId)) {
                return new NodeToolCallReservation(false, 0,
                        "当前执行图已变化，节点工具预算拒绝继续派发");
            }
            TaskNode node = current.graph().byId(nodeId);
            if (node == null || node.status() != TaskNodeStatus.RUNNING) {
                return new NodeToolCallReservation(false, 0,
                        "当前节点不处于运行态，节点工具预算拒绝继续派发");
            }
            Map<String, Integer> budgets = readNodeToolBudgets(current.execution()
                    .get(EXECUTION_NODE_TOOL_BUDGETS));
            String currentPlanPrefix = planVersion + ":";
            budgets.keySet().removeIf(key -> !key.startsWith(currentPlanPrefix));
            int used = budgets.getOrDefault(budgetKey, 0);
            if (used >= limit) {
                return new NodeToolCallReservation(false, used,
                        "节点「" + node.name() + "」已达到工具调用上限 " + limit
                                + "；任务已停止以避免继续空转。修正验收目标或用户指令后再重试。");
            }
            budgets.put(budgetKey, used + 1);
            Map<String, Object> execution = new java.util.LinkedHashMap<>(current.execution());
            execution.put(EXECUTION_NODE_TOOL_BUDGETS, budgets);
            try {
                DomainEvent event = new DomainEvent("ev_" + UUID.randomUUID().toString().substring(0, 8),
                        DomainEventType.NODE_TOOL_CALL_RESERVED, null, nodeId,
                        Map.of("planVersion", planVersion, "callsUsed", used + 1, "maxCalls", limit), null);
                StateSnapshot committed = commit(sessionId, current.version(),
                        current.withExecution(execution), List.of(event));
                return new NodeToolCallReservation(true, used + 1, "");
            } catch (VersionConflictException conflict) {
                // A concurrent state transition may have committed between the read
                // and reservation. Retry against the authoritative snapshot.
            }
        }
        return new NodeToolCallReservation(false, 0,
                "无法可靠保存节点工具预算，已按安全策略停止继续派发");
    }

    private static Map<String, Integer> readNodeToolBudgets(Object value) {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        if (!(value instanceof Map<?, ?> values)) return result;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (entry.getKey() == null || !(entry.getValue() instanceof Number number)) continue;
            int count = number.intValue();
            if (count > 0) result.put(entry.getKey().toString(), count);
        }
        return result;
    }

    /** 当前是否有整图计划等待用户批准。 */
    public boolean hasPendingPlanApproval(String sessionId) {
        return get(sessionId)
                .map(s -> PLAN_APPROVAL_PENDING.equals(
                        s.execution().get(EXECUTION_PLAN_APPROVAL)))
                .orElse(false);
    }

    public StateSnapshot init(String sessionId, String executionId, Goal goal, TaskGraph graph) {
        return init(sessionId, executionId, goal, graph, TaskIntent.fromGoal(goal),
                GoalCompiler.CompilationSource.RESUMED, false);
    }

    public StateSnapshot init(String sessionId, String executionId, Goal goal, TaskGraph graph,
                              TaskIntent intent) {
        return init(sessionId, executionId, goal, graph, intent,
                GoalCompiler.CompilationSource.RESUMED, false);
    }

    public StateSnapshot init(String sessionId, String executionId, Goal goal, TaskGraph graph,
                              TaskIntent intent, GoalCompiler.CompilationSource source,
                              boolean degraded) {
        String k = key(sessionId);
        StateSnapshot snap = new StateSnapshot(
                1L, k, executionId, goal, graph,
                Map.of(), Map.of(), List.of(), 0, PlanRevision.initial("initial_compile"),
                intent, source, degraded);
        DomainEvent ev = new DomainEvent(
                "ev_" + UUID.randomUUID().toString().substring(0, 8),
                DomainEventType.GRAPH_COMPILED, null, null,
                Map.of("nodes", graph.nodes().size(), "version", 1L, "planVersion", snap.planVersion()), null);
        List<DomainEvent> events = List.of(ev);
        if (persistence != null) {
            persistence.replace(k, snap, events);
        } else {
            synchronized (memory) {
                memory.put(k, snap);
                memoryEvents.put(k, new ArrayList<>(events));
            }
        }
        return snap;
    }

    /**
     * CAS：仅当 expectedVersion 匹配时提交新快照（版本自动 +1）。
     */
    public StateSnapshot commit(String sessionId, long expectedVersion, StateSnapshot next) {
        return commit(sessionId, expectedVersion, next, List.of());
    }

    /** Graph transition and its business audit events succeed or fail as one persistence operation. */
    public StateSnapshot commit(String sessionId, long expectedVersion, StateSnapshot next, List<DomainEvent> appended) {
        String k = key(sessionId);
        Objects.requireNonNull(k, "sessionId");
        Objects.requireNonNull(next, "next");
        List<DomainEvent> additions = appended == null ? List.of() : List.copyOf(appended);
        if (persistence != null)
            return commitPersistent(k, expectedVersion, next, additions);
        return commitMemory(k, expectedVersion, next, additions);
    }

    private StateSnapshot commitPersistent(String sessionId, long expectedVersion, StateSnapshot next, List<DomainEvent> appended) {
        PlannerStatePersistence.Bundle cur = persistence.load(sessionId)
                .orElseThrow(() -> new IllegalStateException("no planner state for session " + sessionId));
        if (cur.snapshot().version() != expectedVersion)
            throw new VersionConflictException(expectedVersion, cur.snapshot().version());

        StateSnapshot committed = buildCommitted(sessionId, cur.snapshot(), next);
        List<DomainEvent> events = new ArrayList<>(appended);
        events.add(new DomainEvent(
                "ev_" + UUID.randomUUID().toString().substring(0, 8),
                DomainEventType.STATE_COMMITTED, null, null,
                Map.of("version", committed.version(), "planVersion", committed.planVersion()), null));
        if (!persistence.compareAndSetAppending(sessionId, expectedVersion, cur.snapshot().executionId(), committed, events)) {
            long actual = persistence.load(sessionId)
                    .map(b -> b.snapshot().version()).orElse(-1L);
            throw new VersionConflictException(expectedVersion, actual);
        }
        return committed;
    }

    private StateSnapshot commitMemory(String sessionId, long expectedVersion, StateSnapshot next, List<DomainEvent> appended) {
        synchronized (memory) {
        for (;;) {
            StateSnapshot cur = memory.get(sessionId);
            if (cur == null)
                throw new IllegalStateException("no planner state for session " + sessionId);
            if (cur.version() != expectedVersion)
                throw new VersionConflictException(expectedVersion, cur.version());
            StateSnapshot committed = buildCommitted(sessionId, cur, next);
            if (memory.replace(sessionId, cur, committed)) {
                List<DomainEvent> events = new ArrayList<>(appended);
                events.add(new DomainEvent(
                        "ev_" + UUID.randomUUID().toString().substring(0, 8),
                        DomainEventType.STATE_COMMITTED, null, null,
                        Map.of("version", committed.version(), "planVersion", committed.planVersion()), null));
                memoryEvents.compute(sessionId, (ignored, current) -> PlannerEventHistory.append(current, events));
                return committed;
            }
        }
        }
    }

    private static StateSnapshot buildCommitted(String sessionId, StateSnapshot cur, StateSnapshot next) {
        if (next.sessionId() != null && !next.sessionId().isBlank()
                && !Objects.equals(sessionId, next.sessionId())) {
            throw new IllegalArgumentException("sessionId cannot change within a planner state stream");
        }
        if (cur.executionId() != null && next.executionId() != null
                && !Objects.equals(cur.executionId(), next.executionId())) {
            throw new IllegalArgumentException("executionId cannot change within a planner state stream");
        }
        PlanRevision revision = next.planRevision();
        if (revision.planVersion() < cur.planVersion()
                || revision.planVersion() > cur.planVersion() + 1) {
            throw new IllegalArgumentException("planVersion must increase by at most one");
        }
        if (revision.planVersion() == cur.planVersion() && !samePlanSemantics(cur, next)) {
            throw new IllegalArgumentException("plan semantic fields changed without a new planVersion");
        }
        if (revision.planVersion() == cur.planVersion() + 1
                && revision.parentPlanVersion() != cur.planVersion()) {
            throw new IllegalArgumentException("planRevision parent does not match current plan");
        }
        return new StateSnapshot(
                cur.version() + 1,
                sessionId,
                next.executionId() != null ? next.executionId() : cur.executionId(),
                next.goal() != null ? next.goal() : cur.goal(),
                next.graph() != null ? next.graph() : cur.graph(),
                next.execution(),
                next.environment(),
                next.knowledgeRefs(),
                next.recoveryCount(), revision, next.intent(), next.compilationSource(), next.degraded());
    }

    private static boolean samePlanSemantics(StateSnapshot left, StateSnapshot right) {
        return Objects.equals(left.goal(), right.goal())
                && Objects.equals(left.intent(), right.intent())
                && sameGraphSemantics(left.graph(), right.graph());
    }

    private static boolean sameGraphSemantics(TaskGraph left, TaskGraph right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.nodes().size() != right.nodes().size()) {
            return false;
        }
        for (int i = 0; i < left.nodes().size(); i++) {
            TaskNode a = left.nodes().get(i);
            TaskNode b = right.nodes().get(i);
            if (!Objects.equals(a.id(), b.id()) || !Objects.equals(a.name(), b.name())
                    || !Objects.equals(a.capability(), b.capability())
                    || !Objects.equals(a.dependsOn(), b.dependsOn())
                    || !Objects.equals(a.inputs(), b.inputs()) || !Objects.equals(a.outputs(), b.outputs())
                    || !Objects.equals(a.doneWhen(), b.doneWhen()) || !Objects.equals(a.toolHint(), b.toolHint())
                    || !Objects.equals(a.toolArguments(), b.toolArguments())
                    || !Objects.equals(a.compensation(), b.compensation()) || !Objects.equals(a.covers(), b.covers())
                    || !Objects.equals(a.procedureId(), b.procedureId())
                    || !Objects.equals(a.procedureVersion(), b.procedureVersion())
                    || !Objects.equals(a.evolutionProposalId(), b.evolutionProposalId())
                    || !Objects.equals(a.evolutionCandidateHash(), b.evolutionCandidateHash())
                    || !Objects.equals(a.procedureVariant(), b.procedureVariant())
                    || !Objects.equals(a.procedureSnapshotJson(), b.procedureSnapshotJson())
                    || a.canaryTargetPercent() != b.canaryTargetPercent()) {
                return false;
            }
        }
        return true;
    }

    public List<DomainEvent> events(String sessionId) {
        String k = key(sessionId);
        if (k == null) {
            return List.of();
        }
        if (persistence != null)
            return persistence.load(k)
                    .map(PlannerStatePersistence.Bundle::events)
                    .orElse(List.of());
        return List.copyOf(memoryEvents.getOrDefault(k, List.of()));
    }

    public void appendEvent(String sessionId, DomainEvent event) {
        appendEventInternal(sessionId, null, event);
    }

    /** Caller-bound audit append: never move a stale event into a new scope or execution. */
    public boolean appendEvent(String sessionId, StateSnapshot expected, DomainEvent event) {
        return appendEventInternal(sessionId, Objects.requireNonNull(expected, "expected"), event);
    }

    private boolean appendEventInternal(String sessionId, StateSnapshot expected, DomainEvent event) {
        String k = key(sessionId);
        if (k == null || event == null) {
            return false;
        }
        if (expected != null && !Objects.equals(k, expected.sessionId())) return false;
        if (persistence != null) {
            String executionId = expected == null ? null : expected.executionId();
            boolean identityRead = expected != null;
            for (int i = 0; i < 3; i++) {
                Optional<PlannerStatePersistence.Bundle> loaded = persistence.load(k);
                if (loaded.isEmpty()) {
                    return false;
                }
                if (identityRead && !Objects.equals(executionId, loaded.get().snapshot().executionId())) return false;
                executionId = loaded.get().snapshot().executionId();
                identityRead = true;
                long ver = loaded.get().snapshot().version();
                if (persistence.appendEvents(k, ver, executionId, List.of(event))) {
                    return true;
                }
            }
            return false;
        }
        synchronized (memory) {
            StateSnapshot current = memory.get(k);
            if (current == null || (expected != null && !Objects.equals(expected.executionId(), current.executionId())))
                return false;
            memoryEvents.compute(k, (x, v) -> appendLocal(v, event));
            return true;
        }
    }

    /**
     * 清掉该会话<b>当前任务</b>的规划状态。
     *
     * <p>注意这是「显式重置」用的，不是换任务的必要步骤 ——
     * 换任务靠作用域键自动隔离，没有任何调用方需要记得清。
     * 实测这个方法在主代码里零调用，保留是给运维/后台重置用。</p>
     */
    public void clear(String sessionId) {
        String k = key(sessionId);
        if (k == null) {
            return;
        }
        if (persistence != null) {
            persistence.delete(k);
        }
        synchronized (memory) {
            memory.remove(k);
            memoryEvents.remove(k);
        }
    }

    public boolean hasIncompleteGraph(String sessionId) {
        String k = key(sessionId);
        if (k == null || k.isBlank()) {
            return false;
        }
        return get(sessionId)
                .filter(s -> s.graph() != null && !s.graph().isEmpty())
                .filter(s -> !s.graph().allTerminalSuccess())
                .isPresent();
    }

    /** 当前活跃会话数（内存中的 snapshot 数量）。 */
    public int activeSessionCount() {
        return memory.size();
    }

    private static List<DomainEvent> appendLocal(List<DomainEvent> cur, DomainEvent event) {
        return PlannerEventHistory.append(cur, List.of(event));
    }
}
