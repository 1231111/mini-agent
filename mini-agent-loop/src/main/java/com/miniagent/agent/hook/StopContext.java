package com.miniagent.agent.hook;

import com.miniagent.agent.permission.PermissionMode;

import java.util.Set;

/**
 * 一轮无工具收尾时交给 StopHook 的只读上下文。
 */
public record StopContext(
        String sessionId,
        String finalText,
        int turn,
        int maxIterations,
        Set<String> toolsInvoked,
        boolean writeFileSucceeded,
        boolean mediaDelivered,
        boolean requiresStructuredPlan,
        boolean subagent,
        PermissionMode permissionMode,
        boolean planApproved
) {}
