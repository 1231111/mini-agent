package com.miniagent.agent.planner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.core.LoopTurnPolicy;
import com.miniagent.agent.tool.ToolConcurrencyPolicy;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Loop policy used when a recovery node may repeat a side effect.
 *
 * <p>The policy wraps the normal proposal policy, but its side-effect admission
 * is independent of model text.  Read-only calls are recorded as paired
 * assistant-call/tool-result observations.  A write or external action can be
 * admitted only once that evidence is complete and scoped to the current
 * execution, plan revision and node.  The readback is consumed before dispatch
 * of the side effect, so a failed or timed-out mutation cannot accidentally
 * reuse an old observation on the next retry.</p>
 */
public final class RecoveryReadbackPolicy implements LoopTurnPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicLong IDS = new AtomicLong();
    private static final Set<String> TODO_TOOLS = Set.of("todo");
    private static final int MAX_LIVE_MESSAGES = 256;

    private final StateSnapshot snapshot;
    private final TaskNode node;
    private final LoopTurnPolicy delegate;
    private final RecoveryExecutionGuard.Verification predecessor;
    private final List<ChatMessage> liveMessages = new ArrayList<>();
    private boolean readbackConsumed;

    public RecoveryReadbackPolicy(StateSnapshot snapshot, TaskNode node,
                                  LoopTurnPolicy delegate) {
        this(snapshot, node, delegate, null);
    }

    /**
     * {@code predecessor} must be supplied only when the caller has checked
     * that it came from a SUCCESS dependency in this same execution and plan.
     */
    public RecoveryReadbackPolicy(StateSnapshot snapshot, TaskNode node,
                                  LoopTurnPolicy delegate,
                                  RecoveryExecutionGuard.Verification predecessor) {
        this.snapshot = snapshot;
        this.node = node;
        this.delegate = delegate == null ? LoopTurnPolicy.NONE : delegate;
        this.predecessor = predecessor;
        this.readbackConsumed = false;
    }

    @Override public List<String> allowedTools() { return delegate.allowedTools(); }
    @Override public boolean forceToolsOnly() { return delegate.forceToolsOnly(); }
    @Override public boolean hardGate() { return delegate.hardGate(); }
    @Override public String denyTool(String toolName) { return delegate.denyTool(toolName); }
    @Override public String denyTodoArgs(String argumentsJson) { return delegate.denyTodoArgs(argumentsJson); }

    @Override
    public String denyToolInvocation(String toolName, String argumentsJson) {
        String guard = guard(toolName, argumentsJson);
        if (guard != null) return guard;
        return delegate.denyToolInvocation(toolName, argumentsJson);
    }

    @Override
    public String denyRewrittenToolInvocation(String toolName, String argumentsJson) {
        String guard = guard(toolName, argumentsJson);
        if (guard != null) return guard;
        String denied = delegate.denyRewrittenToolInvocation(toolName, argumentsJson);
        if (denied == null && !TODO_TOOLS.contains(normalizeTool(toolName))
                && !ToolConcurrencyPolicy.isReadOnly(normalizeTool(toolName), argumentsJson)) consumeReadback();
        return denied;
    }

    @Override
    public synchronized String observeToolOutcome(String toolName, String argumentsJson,
                                                   ToolResult result) {
        return observeToolOutcome(toolName, argumentsJson, result,
                result == null ? "" : result.legacyText());
    }

    @Override
    public synchronized String observeToolOutcome(String toolName, String argumentsJson,
                                                   ToolResult result, String visibleResult) {
        if (!TODO_TOOLS.contains(normalizeTool(toolName)) && result != null) {
            boolean readOnly = ToolConcurrencyPolicy.isReadOnly(normalizeTool(toolName), argumentsJson);
            if (readOnly && (readbackConsumed || !result.isSuccess())) liveMessages.clear();
            if (readOnly) readbackConsumed = false;
            String id = "recovery-readback-" + IDS.incrementAndGet();
            String args = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
            if (args.length() > 8_000 || result.legacyText().length() > 24_000) {
                // ExecutionEvidence bounds apply before materialising call/result
                // objects, so a large result cannot grow this policy unboundedly.
                liveMessages.clear();
                readbackConsumed = true;
                return delegate.observeToolOutcome(toolName, argumentsJson, result, visibleResult);
            }
            liveMessages.add(AiMessage.from(ToolExecutionRequest.builder()
                    .id(id).name(normalizeTool(toolName)).arguments(args).build()));
            liveMessages.add(ToolExecutionResultMessage.from(id, normalizeTool(toolName),
                    result.legacyText()));
            if (liveMessages.size() > MAX_LIVE_MESSAGES) {
                // Do not retain an unbounded history or turn a truncated subset
                // into complete proof. A new read is cheap and explicit.
                liveMessages.clear();
                readbackConsumed = true;
            }
            if (!readOnly) {
                // The world may have changed even when the tool returned an
                // error or timeout.  Force a fresh read before another write.
                readbackConsumed = true;
                liveMessages.clear();
            }
        }
        return delegate.observeToolOutcome(toolName, argumentsJson, result, visibleResult);
    }

    /** Current paired evidence, useful for audit and for the parent dispatch layer. */
    public synchronized RecoveryExecutionGuard.Verification liveVerification() {
        ExecutionEvidence live = liveMessages.isEmpty()
                ? ExecutionEvidence.claims("")
                : ExecutionEvidence.fromEnvelope(ExecutionEvidence.build("", List.copyOf(liveMessages)));
        if (!readbackConsumed && !live.observations().isEmpty()) {
            return new RecoveryExecutionGuard.Verification(snapshot.executionId(), snapshot.planVersion(),
                    node.id(), live);
        }
        if (!readbackConsumed && predecessor != null && sameScope(predecessor)) {
            return new RecoveryExecutionGuard.Verification(snapshot.executionId(), snapshot.planVersion(),
                    node.id(), predecessor.evidence());
        }
        return new RecoveryExecutionGuard.Verification(snapshot.executionId(), snapshot.planVersion(),
                node.id(), ExecutionEvidence.claims(""));
    }

    public synchronized boolean hasFreshReadback() {
        RecoveryExecutionGuard.Verification verification = liveVerification();
        return verification.evidence().completeness().complete()
                && verification.evidence().observations().stream().anyMatch(o -> o.success()
                && ToolConcurrencyPolicy.isReadOnly(o.toolName(), o.arguments()));
    }

    @Override public String stopReason() { return delegate.stopReason(); }
    @Override public StopKind stopKind() { return delegate.stopKind(); }
    @Override public String focusLabel() { return delegate.focusLabel(); }
    @Override public Set<Integer> focusTodoIds() { return delegate.focusTodoIds(); }
    @Override public void markDrift() { delegate.markDrift(); }
    @Override public int driftHits() { return delegate.driftHits(); }
    @Override public boolean consumeDrift() { return delegate.consumeDrift(); }
    @Override public int actionTimeoutSeconds() { return delegate.actionTimeoutSeconds(); }

    private synchronized String guard(String toolName, String argumentsJson) {
        if (TODO_TOOLS.contains(normalizeTool(toolName))) return null;
        Map<String, Object> arguments;
        try {
            arguments = JSON.readValue(argumentsJson == null ? "{}" : argumentsJson,
                    new TypeReference<>() {});
        } catch (Exception invalid) {
            // Let the delegate issue its normal malformed-argument diagnostic;
            // no side effect may pass an unparseable target to the guard.
            return ToolConcurrencyPolicy.isReadOnly(normalizeTool(toolName), argumentsJson)
                    ? null : denied("恢复节点副作用参数无法可靠解析，拒绝执行");
        }
        ActionSpec action = new ActionSpec("recovery-guard", node.id(), normalizeTool(toolName),
                node.capability(), arguments, node.doneWhen(), "", "", 0,
                ActionRetryPolicy.none(), snapshot.sessionId() + ":" + node.id());
        RecoveryExecutionGuard.Check check = RecoveryExecutionGuard.check(snapshot, node, action,
                liveVerification());
        if (check.allowed()) return null;
        return denied(check.reason());
    }

    private synchronized void consumeReadback() {
        // Called only at the final, post-hook admission boundary. Consuming in
        // the first check would make the same pipeline invocation fail when it
        // revalidates its rewritten arguments.
        readbackConsumed = true;
        liveMessages.clear();
    }

    private boolean sameScope(RecoveryExecutionGuard.Verification value) {
        return value != null && snapshot != null
                && snapshot.executionId().equals(value.executionId())
                && snapshot.planVersion() == value.planVersion();
    }

    private static String normalizeTool(String toolName) {
        return toolName == null ? "" : toolName.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static String denied(String message) {
        return ToolResult.failure(ToolErrorCode.PERMISSION_DENIED, message, false).legacyText();
    }
}
