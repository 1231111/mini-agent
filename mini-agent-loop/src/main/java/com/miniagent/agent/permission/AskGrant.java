package com.miniagent.agent.permission;

/**
 * A narrowly scoped approval for one exact tool invocation.
 *
 * <p>The token is intentionally opaque.  The tool name and argument digest
 * are kept with the grant so that a caller can never turn an approval for one
 * invocation into a standing approval for the whole tool.</p>
 */
public record AskGrant(
        String token,
        String toolName,
        String argumentsDigest,
        long expiresAtEpochMillis) {

    public AskGrant {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("Ask grant token is required");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Ask grant tool is required");
        }
        if (argumentsDigest == null || argumentsDigest.isBlank()) {
            throw new IllegalArgumentException("Ask grant argument digest is required");
        }
        if (expiresAtEpochMillis <= 0L) {
            throw new IllegalArgumentException("Ask grant expiry is required");
        }
    }

    public boolean expired(long now) {
        return expiresAtEpochMillis <= now;
    }
}
