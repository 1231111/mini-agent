package com.miniagent.memory.port;

import java.util.Objects;

/**
 * 把一条 SOP 的某个版本投影到记忆服务的命令。
 *
 * <p><b>为什么需要它</b>：结算发生在记忆服务侧，而 {@code procedureId} / {@code procedureVersion}
 * 是执行侧在规划时绑定的。服务端如果不知道这些 ID 对应哪一行、哪一版、属于谁，
 * 就只能按「当前版本」猜——猜错就把 v1 的结果记到 v2 头上。投影是结算的前置条件，
 * 不是可选的缓存。</p>
 *
 * <p><b>为什么 scope 必须显式传</b>：SOP 可能是 {@code USER} 或 {@code PROJECT} 归属。
 * 只传 {@code userId} 会让项目级 SOP 被当成用户级，服务端按 {@code scope_id = userId}
 * 找不到行，结算静默变成「过期版本」。归属是数据的一部分，不能在传输层丢掉。</p>
 */
public record ProcedureDefinitionCommand(
        MemoryPrincipal principal,
        long procedureId,
        int procedureVersion,
        String scopeType,
        String scopeId,
        String name,
        String description,
        String preconditionsJson,
        String stepsJson,
        String successConditionsJson,
        double importance,
        String status
) {
    public ProcedureDefinitionCommand {
        principal = Objects.requireNonNull(principal, "principal");
        if (procedureId <= 0) {
            throw new IllegalArgumentException("procedureId must be positive");
        }
        if (procedureVersion <= 0) {
            throw new IllegalArgumentException("procedureVersion must be positive");
        }
        scopeType = requireText(scopeType, "scopeType").toUpperCase(java.util.Locale.ROOT);
        scopeId = requireText(scopeId, "scopeId");
        name = requireText(name, "name");
        stepsJson = requireText(stepsJson, "stepsJson");
        status = requireText(status, "status").toUpperCase(java.util.Locale.ROOT);
        description = normalize(description);
        preconditionsJson = normalize(preconditionsJson);
        successConditionsJson = normalize(successConditionsJson);
        if (!"USER".equals(scopeType) && !"PROJECT".equals(scopeType)) {
            throw new IllegalArgumentException("scopeType must be USER or PROJECT");
        }
        requireScopeBelongsToPrincipal(principal, scopeType, scopeId);
        if (importance < 0) {
            throw new IllegalArgumentException("importance must be non-negative");
        }
    }

    /**
     * 归属必须能由已认证身份解释出来：{@code USER} 只能是本人，{@code PROJECT} 必须是当前项目。
     *
     * <p>这条不变量放在契约里而不是只放服务端：调用侧一旦传错，应该在创建命令时就失败，
     * 而不是发出一次请求、等对面回 4xx 再排查。服务端仍会独立复核一遍。</p>
     */
    private static void requireScopeBelongsToPrincipal(MemoryPrincipal principal, String scopeType, String scopeId) {
        if ("USER".equals(scopeType)) {
            if (!scopeId.equals(String.valueOf(principal.userId()))) {
                throw new IllegalArgumentException("USER scope must be the caller's own user id");
            }
            return;
        }
        if (!principal.hasProject()) {
            throw new IllegalArgumentException("PROJECT scope requires projectId on the principal");
        }
        if (!scopeId.equals(principal.projectId())) {
            throw new IllegalArgumentException("PROJECT scope must be the caller's current project");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
