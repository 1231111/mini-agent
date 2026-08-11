package com.miniagent.agent.tool;

import java.util.Set;

/**
 * 工具并发安全策略：只读/幂等工具可在流式中途预执行（对齐 Claude Code isConcurrencySafe）。
 */
public final class ToolConcurrencyPolicy {

    private ToolConcurrencyPolicy() {}

    /** 流式 onCompleteToolCall 时可抢跑的只读工具 */
    public static final Set<String> STREAM_PREFETCH_SAFE = Set.of(
            "todo", // set/get 偏规划；update 仍相对轻量。写路径有会话隔离。
            "memory",
            "skill_list", "skill_view",
            "read_file", "list_files", "read_package",
            "search_code", "ast_search", "codebase_search",
            "web_search", "web_extract", "http_get",
            "browser_snapshot", "browser_screenshot"
    );

    public static boolean isStreamPrefetchSafe(String toolName) {
        return toolName != null && STREAM_PREFETCH_SAFE.contains(toolName);
    }
}
