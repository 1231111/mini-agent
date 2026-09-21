package com.miniagent.agent.planner;

import com.miniagent.agent.tool.CapabilityRegistry;
import com.miniagent.agent.tool.ToolErrorCode;

import java.util.Set;

/** Planner 写入 ActionSpec 的重试策略。 */
public record ActionRetryPolicy(int maxAttempts, long initialBackoffMillis,
                                double multiplier, Set<String> retryableErrorCodes) {
    public ActionRetryPolicy {
        maxAttempts = Math.max(1, maxAttempts);
        initialBackoffMillis = Math.max(0, initialBackoffMillis);
        multiplier = multiplier < 1.0 ? 1.0 : multiplier;
        retryableErrorCodes = retryableErrorCodes == null ? Set.of() : Set.copyOf(retryableErrorCodes);
    }
    public static ActionRetryPolicy none() {
        return new ActionRetryPolicy(1, 0, 1, Set.of());
    }

    public static ActionRetryPolicy transientFailures() {
        return new ActionRetryPolicy(3, 500, 2.0,
                Set.of("TIMEOUT", "RATE_LIMITED", "DEPENDENCY_UNAVAILABLE"));
    }

    public static ActionRetryPolicy forCapability(String capability) {
        return CapabilityRegistry.acquires(capability)
                ? transientFailures() : none();
    }

    public boolean retryable(ToolErrorCode code) {
        if (code == null || retryableErrorCodes.isEmpty()) {
            return false;
        }
        return retryableErrorCodes.contains(code.name());
    }

    public boolean withinLocalAttempts(int retryCount) {
        return retryCount < Math.max(0, maxAttempts - 1);
    }
}
