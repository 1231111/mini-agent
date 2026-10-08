package com.miniagent.agent.permission;

import com.miniagent.agent.tool.impl.SongGenerateParams;
import com.miniagent.common.permission.ExecPolicy;

import java.util.Set;
import java.util.Objects;

/**
 * 权限策略：Plan 只读工具集、Ask 危险工具集、exec_command 三档策略。
 */
public final class PermissionPolicy {

    private PermissionPolicy() {}

    /** 终端命令执行工具名。多处判定要用，抽出来免得拼错。 */
    public static final String EXEC_TOOL = "exec_command";

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
            EXEC_TOOL, "http_post",
            "write_file", "edit_file",
            "browser_click", "browser_type", "browser_press", "browser_scroll",
            "browser_evaluate", "browser_extract_text",
            "comfyui_execute", "comfyui_txt2img", "comfyui_img2img", "comfyui_img2video", "comfyui_tts",
            SongGenerateParams.TOOL_NAME,
            "delegate_task"
    );

    public static boolean isPlanSafe(String toolName) {
        return Objects.nonNull(toolName) && PLAN_SAFE_TOOLS.contains(toolName);
    }

    public static boolean isAskDangerous(String toolName) {
        if (Objects.isNull(toolName)) {
            return false;
        }
        // MCP 外部工具默认视为危险（需 Ask 确认或 Plan 批准）
        if (toolName.startsWith("mcp__")) {
            return true;
        }
        return ASK_DANGEROUS_TOOLS.contains(toolName);
    }

    /**
     * 结合会话模式算出 exec_command 的最终生效策略。
     *
     * <p>优先级刻意设计成"<b>BLOCK 是硬闸门，谁都提升不了</b>"：
     * <ul>
     *   <li>{@code configured=BLOCK} → 就是 BLOCK。{@link PermissionMode#ACCEPT_EDITS}
     *       （"自动编辑 / 别问我"）也绕不过 —— 用户显式禁止执行命令，不该被一个
     *       "别问我"的会话模式悄悄打开。</li>
     *   <li>{@code ACCEPT_EDITS} 且不是 BLOCK → 提升为 {@link ExecPolicy#ALLOW}，
     *       保持该模式"跳过危险工具二次询问"的既有语义。</li>
     *   <li>其余按 {@code configured} 原样。</li>
     * </ul>
     *
     * <p>{@code configured} 为 null 时兜底成 {@link ExecPolicy#ASK}（最紧的可批准档）。
     * 正常路径上 {@code ExecPolicyService} 不会传 null，这里只是防御。
     */
    public static ExecPolicy effectiveExecPolicy(PermissionMode mode, ExecPolicy configured) {
        ExecPolicy p = Objects.isNull(configured) ? ExecPolicy.ASK : configured;
        if (p == ExecPolicy.BLOCK) {
            return ExecPolicy.BLOCK;
        }
        if (mode == PermissionMode.ACCEPT_EDITS) {
            return ExecPolicy.ALLOW;
        }
        return p;
    }

    /** 该工具此刻是否被 exec 策略硬禁止（拒绝执行，不给批准入口）。 */
    public static boolean isExecBlocked(String toolName, ExecPolicy effectiveExecPolicy) {
        return EXEC_TOOL.equals(toolName) && effectiveExecPolicy == ExecPolicy.BLOCK;
    }

    /**
     * 执行前是否必须有本会话 grant。
     *
     * <p>传进来的必须是 {@link #effectiveExecPolicy} 的结果（已经计入会话模式），
     * 不要传原始配置值 —— 否则 ACCEPT_EDITS 的提升会失效。
     *
     * <p>BLOCK 不在这里处理：它的语义是"直接拒绝"，不是"弹批准"。
     * 调用方必须先问 {@link #isExecBlocked}（见 {@code ToolPipeline}）。
     * 这里返回 false 只是"不产生批准入口"，不代表放行。
     */
    public static boolean needsSessionGrant(
            PermissionMode mode, String toolName, ExecPolicy effectiveExecPolicy) {
        if (Objects.isNull(toolName)) {
            return false;
        }
        boolean isExec = EXEC_TOOL.equals(toolName);
        ExecPolicy exec = Objects.isNull(effectiveExecPolicy) ? ExecPolicy.ASK : effectiveExecPolicy;

        if (isExec && exec == ExecPolicy.BLOCK) {
            // 禁档不该有"批准一下就放行"的路。真正的拒绝在 isExecBlocked。
            return false;
        }
        if (mode == PermissionMode.ACCEPT_EDITS) {
            return false;
        }
        if (isExec && exec == ExecPolicy.ALLOW) {
            return false;
        }
        if (mode == PermissionMode.ASK && isAskDangerous(toolName)) {
            return true;
        }
        if (isExec) {
            // 走到这里只剩 ASK
            return true;
        }
        return "http_post".equals(toolName);
    }

    /**
     * 当前是否允许将该工具放进本轮 specs。
     * Ask 的「未授权危险工具」仍会出现在 specs 中（让模型能发起），执行时再拦截并推前端确认。
     */
    public static boolean allowInSpecs(PermissionMode mode, boolean planApproved, String toolName) {
        if (Objects.isNull(toolName)) {
            return false;
        }
        if (mode == PermissionMode.PLAN && !planApproved) {
            return isPlanSafe(toolName);
        }
        return true;
    }
}
