package com.miniagent.agent.execution;

import com.miniagent.agent.core.RunScope;

import java.util.Set;

/**
 * 进入 {@link ToolPipeline} 的一次调用。身份只来自 {@link RunScope}，
 * 工具面与探测限额由调用方算好再传入，管道不回头依赖循环。
 */
public record ToolRequest(
        RunScope scope,
        String name,
        String arguments,
        int turn,
        String runId,
        String probeDeny,
        Set<String> allowedTools
) {
    public ToolRequest {
        name = name == null ? "" : name;
        arguments = arguments == null ? "" : arguments;
        runId = runId == null || runId.isBlank() ? "run" : runId;
        probeDeny = probeDeny == null || probeDeny.isBlank() ? null : probeDeny;
        allowedTools = allowedTools == null ? null : Set.copyOf(allowedTools);
        if (scope == null) {
            scope = RunScope.capture().withSession(null);
        }
    }

    public String sessionId() {
        return scope.sessionId();
    }

    public static ToolRequest of(
            RunScope scope, String name, String arguments, int turn, String runId) {
        RunScope s = scope == null ? RunScope.capture() : scope;
        return new ToolRequest(s, name, arguments, turn, runId, null, null);
    }

    public static ToolRequest of(
            String sessionId, String name, String arguments, int turn, String runId) {
        return of(RunScope.capture().withSession(sessionId), name, arguments, turn, runId);
    }

    public static ToolRequest bound(String sessionId, String name, String arguments) {
        return of(RunScope.capture().withSession(sessionId), name, arguments, 0, "bound");
    }

    public static ToolRequest bound(RunScope scope, String name, String arguments) {
        return of(scope, name, arguments, 0, "bound");
    }

    public ToolRequest withProbeDeny(String deny) {
        return new ToolRequest(scope, name, arguments, turn, runId, deny, allowedTools);
    }

    public ToolRequest withAllowedTools(Set<String> names) {
        return new ToolRequest(scope, name, arguments, turn, runId, probeDeny, names);
    }
}
