package com.miniagent.agent.permission;

import java.util.Set;
import java.util.Objects;

/**
 * 权限策略：Plan 只读工具集、Ask 危险工具集。
 */
public final class PermissionPolicy {

    private PermissionPolicy() {}

    /** Plan 未批准前允许的工具（探索 + 计划，禁止写/执行/生图） */
    public static final Set<String> PLAN_SAFE_TOOLS = Set.of(
            "todo", "memory",
            "skill_list", "skill_view",
            "read_file", "list_files", "read_package",
            "search_code", "ast_search", "codebase_search",
            "web_search", "web_extract", "http_get",
            "browser_navigate", "browser_snapshot", "browser_screenshot", "browser_close"
    );

    /** Ask 模式下需用户确认的危险工具 */
    public static final Set<String> ASK_DANGEROUS_TOOLS = Set.of(
            "exec_command",
            "write_file", "edit_file",
            "browser_click", "browser_type", "browser_press", "browser_scroll",
            "browser_evaluate", "browser_extract_text",
            "comfyui_execute", "comfyui_txt2img", "comfyui_img2img", "comfyui_img2video", "comfyui_tts",
            "delegate_task"
    );

    public static boolean isPlanSafe(String toolName) {
        return Objects.nonNull(toolName) && PLAN_SAFE_TOOLS.contains(toolName);
    }

    public static boolean isAskDangerous(String toolName) {
        if (Objects.isNull(toolName)) return false;
        // MCP 外部工具默认视为危险（需 Ask 确认或 Plan 批准）
        if (toolName.startsWith("mcp__")) return true;
        return ASK_DANGEROUS_TOOLS.contains(toolName);
    }

    /**
     * 当前是否允许将该工具放进本轮 specs。
     * Ask 的「未授权危险工具」仍会出现在 specs 中（让模型能发起），执行时再拦截并推前端确认。
     */
    public static boolean allowInSpecs(PermissionMode mode, boolean planApproved, String toolName) {
        if (Objects.isNull(toolName)) return false;
        if (mode == PermissionMode.PLAN && !planApproved) {
            return isPlanSafe(toolName);
        }
        return true;
    }
}
