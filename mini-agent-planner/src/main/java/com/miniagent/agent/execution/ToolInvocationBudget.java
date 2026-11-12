package com.miniagent.agent.execution;

import com.miniagent.agent.tool.ToolDescriptor;
import com.miniagent.agent.tool.ToolErrorCode;
import com.miniagent.agent.tool.ToolResult;
import com.miniagent.agent.tool.ToolSideEffect;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Per-invocation handler deadline. Approval, pause checkpoints and lock waits are separate. */
public final class ToolInvocationBudget {
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlines();
    private final long timeoutSeconds;
    private ScheduledFuture<?> deadline;
    private Thread handler;
    private boolean active;
    private boolean expired;

    public ToolInvocationBudget(long timeoutSeconds) {
        this.timeoutSeconds = Math.max(1L, timeoutSeconds);
    }

    public long timeoutSeconds() { return timeoutSeconds; }

    /** Called on the executing thread only once, immediately before the handler. */
    public synchronized void start() {
        if (active || deadline != null) throw new IllegalStateException("Tool invocation budget already started");
        handler = Thread.currentThread();
        active = true;
        deadline = DEADLINES.schedule(this::expire, timeoutSeconds, TimeUnit.SECONDS);
    }

    public synchronized void finish() {
        active = false;
        handler = null;
        if (deadline != null) deadline.cancel(false);
    }

    public synchronized boolean expired() { return expired; }

    /** The loop and journal must report the same contract when this deadline fires. */
    public ToolResult timeoutResult(ToolDescriptor descriptor) {
        return timeoutResult(descriptor, timeoutSeconds);
    }

    public static ToolResult timeoutResult(ToolDescriptor descriptor, long timeoutSeconds) {
        String message = "工具执行超时（" + timeoutSeconds + "s）";
        // A handler deadline says nothing about whether a submitted write took
        // effect. VERIFY/RESUME are recovery instructions, not proof that retry
        // is safe; their timeout must still stop automatic action dispatch.
        if (descriptor.sideEffect() != ToolSideEffect.READ_ONLY) {
            return ToolResult.unknown(message + descriptor.recoveryHint(), null);
        }
        return switch (descriptor.timeoutRecovery()) {
            case AWAIT_USER -> ToolResult.failure(ToolErrorCode.CANCELLED,
                    "等待用户回答已结束，请根据用户下一条消息继续", false);
            case ABORT -> ToolResult.unknown(message + descriptor.recoveryHint(), null);
            default -> ToolResult.failure(ToolErrorCode.TIMEOUT,
                    message + descriptor.recoveryHint(), true);
        };
    }

    private synchronized void expire() {
        if (!active) return;
        expired = true;
        handler.interrupt();
    }

    private static ScheduledThreadPoolExecutor deadlines() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "tool-handler-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
