package com.miniagent.application;

import com.miniagent.agent.core.GoalContinuationContext;
import com.miniagent.agent.planner.GoalContinuationPolicy;
import com.miniagent.agent.planner.StateSnapshot;
import com.miniagent.common.RunStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

/** Durable goal controller. It wakes only eligible goals and never expands tool authority. */
@Service
public class AutonomousGoalService {
    private static final Logger LOG = LoggerFactory.getLogger(AutonomousGoalService.class);
    private static final Set<String> RETRYABLE = Set.of("OUTER_ROUNDS_EXHAUSTED", "QUOTA_ABORT",
            "JUDGE_UNAVAILABLE", "JUDGE_INFRASTRUCTURE_FAILURE", "MODEL_CALL_TIMEOUT");
    public record Outcome(RunStatus status, String reason, StateSnapshot snapshot) { }
    @FunctionalInterface public interface Runner { Outcome run(GoalQueue.Goal goal); }
    private record Active(GoalQueue.Goal goal, Thread thread) { }
    private final GoalQueue queue;
    private final Runner runner;
    private final Executor executor;
    private final Clock clock;
    private final Semaphore slots = new Semaphore(2);
    private final Map<String, Active> active = new ConcurrentHashMap<>();
    @Value("${agent.goals.enabled:true}") private boolean enabled = true;
    @Value("${agent.goals.window-ms:900000}") private long windowMillis = 900_000;
    @Value("${agent.goals.retry-delay-ms:5000}") private long retryDelayMillis = 5000;
    @Value("${agent.goals.lease-ms:30000}") private long leaseMillis = 30_000;
    @Value("${agent.goals.max-wakeups:4}") private int maxWakeups = 4;
    @Value("${agent.goals.max-no-progress:2}") private int maxNoProgress = 2;

    @Autowired public AutonomousGoalService(GoalQueue queue, ObjectProvider<AgentChatApplicationService> chats,
                                             @Qualifier("agentRunExecutor") Executor executor) {
        this(queue, goal -> chats.getObject().resumeGoal(goal), executor, Clock.systemUTC());
    }
    AutonomousGoalService(GoalQueue queue, Runner runner, Executor executor, Clock clock) {
        this.queue = queue; this.runner = runner; this.executor = executor; this.clock = clock;
    }

    public GoalQueue.State recordUserOutcome(long user, String tenant, String session, long task,
                                             String round, String workspace, RunStatus status, String reason,
                                             StateSnapshot snapshot) {
        if (!enabled || snapshot == null || snapshot.goal() == null || snapshot.graph().isEmpty())
            return GoalQueue.State.WAITING_HUMAN;
        long now = clock.millis();
        GoalQueue.State state = status == RunStatus.SUCCESS ? GoalQueue.State.COMPLETED
                : status == RunStatus.CANCELED ? GoalQueue.State.CANCELLED
                : canWake(status, reason, snapshot) ? GoalQueue.State.QUEUED : GoalQueue.State.WAITING_HUMAN;
        queue.register(new GoalQueue.Goal(hash(session + ":" + task + ":" + snapshot.executionId()), user,
                tenant, session, task, snapshot.executionId(), round, snapshot.goal().objective(), workspace,
                state, state == GoalQueue.State.QUEUED ? "UNFINISHED_SAFE_WORK" : safeReason(reason),
                now + delay(), now + Math.max(1000, Math.min(3_600_000, windowMillis)), 0, 0,
                progress(snapshot), null, 0, 0), now);
        return state;
    }
    static boolean canWake(RunStatus status, String reason, StateSnapshot snapshot) {
        return status == RunStatus.FAILURE && RETRYABLE.contains(reason)
                && GoalContinuationPolicy.blockReason(snapshot).isEmpty();
    }
    public void userRequest(long user, String session) { queue.suspendForUser(user, session, clock.millis()); }
    public void cancel(long user, String session) {
        queue.cancel(user, session, clock.millis());
        active.values().stream().filter(a -> a.goal().userId() == user && a.goal().sessionId().equals(session))
                .forEach(a -> a.thread().interrupt());
    }
    /**
     * Subject erasure fence: revoke durable rows and interrupt in-flight workers
     * before the caller removes the subject's conversations and memory.
     */
    public void eraseOwner(long user, String tenant) {
        active.values().stream()
                .filter(a -> a.goal().userId() == user && tenant.equals(a.goal().tenantId()))
                .forEach(a -> a.thread().interrupt());
        queue.eraseOwner(user, tenant, clock.millis());
    }

    @Scheduled(fixedDelayString = "${agent.goals.scan-ms:1000}")
    public void tick() {
        if (!enabled) return;
        long now = clock.millis();
        for (Active work : active.values()) {
            try {
                if (!queue.renew(work.goal(), now, lease())) work.thread().interrupt();
            } catch (RuntimeException unavailable) {
                work.thread().interrupt(); // losing the authority store never grants more work
                LOG.warn("Cannot renew goal claim {}", work.goal().id(), unavailable);
            }
        }
        for (GoalQueue.Goal due : queue.due(now, 8)) {
            if (!slots.tryAcquire()) return;
            var claimed = queue.claim(due, now, lease(), Math.max(1, Math.min(20, maxWakeups)));
            if (claimed.isEmpty()) { slots.release(); continue; }
            GoalQueue.Goal goal = claimed.get();
            try { executor.execute(() -> execute(goal)); }
            catch (RuntimeException rejected) {
                slots.release();
                queue.finish(goal, GoalQueue.State.QUEUED, "WORKER_BUSY", goal.progressHash(),
                        goal.noProgress(), now + delay(), now);
            }
        }
    }

    private void execute(GoalQueue.Goal goal) {
        active.put(goal.id(), new Active(goal, Thread.currentThread()));
        try {
            if (!queue.renew(goal, clock.millis(), lease())) return;
            Outcome outcome = runner.run(goal);
            StateSnapshot snapshot = outcome.snapshot();
            String nextHash = snapshot == null ? goal.progressHash() : progress(snapshot);
            int stagnant = nextHash.equals(goal.progressHash()) ? goal.noProgress() + 1 : 0;
            GoalQueue.State next = outcome.status() == RunStatus.SUCCESS ? GoalQueue.State.COMPLETED
                    : outcome.status() == RunStatus.CANCELED ? GoalQueue.State.CANCELLED
                    : canWake(outcome.status(), outcome.reason(), snapshot)
                        && stagnant < Math.max(1, maxNoProgress) && goal.deadline() > clock.millis()
                        ? GoalQueue.State.QUEUED : GoalQueue.State.WAITING_HUMAN;
            String reason = stagnant >= Math.max(1, maxNoProgress) ? "NO_PROGRESS" : safeReason(outcome.reason());
            queue.finish(goal, next, reason, nextHash, stagnant,
                    clock.millis() + backoff(goal.attempts()), clock.millis());
        } catch (RuntimeException failure) {
            // Do not infer safe replay from an exception: the next attempt must be explicitly reviewed.
            queue.finish(goal, GoalQueue.State.WAITING_HUMAN, "CONTINUATION_FAILED", goal.progressHash(),
                    goal.noProgress() + 1, clock.millis() + delay(), clock.millis());
            LOG.warn("Goal continuation stopped id={} session={}", goal.id(), goal.sessionId(), failure);
        } finally {
            active.remove(goal.id()); slots.release(); Thread.interrupted();
        }
    }
    private long delay() { return Math.max(100, Math.min(60_000, retryDelayMillis)); }
    private long lease() { return Math.max(5000, Math.min(120_000, leaseMillis)); }
    private long backoff(int attempts) { return Math.min(60_000, delay() * (1L << Math.min(4, Math.max(0, attempts - 1)))); }
    private static String safeReason(String reason) { return reason == null || reason.isBlank() ? "UNFINISHED" : reason.substring(0, Math.min(100, reason.length())); }
    public static String progress(StateSnapshot snapshot) {
        if (snapshot == null) return "";
        // A new plan/version alone is not progress. Require changed accepted output or node state.
        return hash(snapshot.graph().nodes().stream().sorted(java.util.Comparator.comparing(com.miniagent.agent.planner.TaskNode::id))
                .map(n -> n.id() + ":" + n.status() + ":" + n.output()).collect(java.util.stream.Collectors.joining("\n")));
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
