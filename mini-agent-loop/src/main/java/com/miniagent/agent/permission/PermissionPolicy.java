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
     * <p><b>exec 策略是用户对"模型发起的命令"的独立显式控制，会话模式不得提升它。</b>
     * 曾经的实现让 {@link PermissionMode#ACCEPT_EDITS}（UI 文案"自动编辑"）把任何档位提升为
     * {@link ExecPolicy#ALLOW}，于是用户为了"别弹编辑确认"切一个模式，就顺带把任意命令执行
     * 变成了免批 —— 与本类 {@link ExecPolicy} 的注释（"ACCEPT_EDITS 的含义是'别问我'，
     * 不是'我允许你执行命令'"）直接矛盾，也让 prod 档的 ask 形同虚设。
     * 想免批执行命令请显式把 exec 策略切成 allow，而不是靠会话模式"顺带"获得。</p>
     *
     * <p>唯一保留的硬约束仍是：{@code configured=BLOCK} 谁都提升不了。</p>
     */
    public static ExecPolicy effectiveExecPolicy(PermissionMode mode, ExecPolicy configured) {
        return Objects.isNull(configured) ? ExecPolicy.ASK : configured;
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
        if (isExec) {
            // exec 只认 exec 策略：ask 就必须拿到本会话 grant。
            // 刻意不在这里看会话模式 —— ACCEPT_EDITS（"自动编辑"）跳过的是编辑类工具的二次询问，
            // 不是"任意 shell 免批"；那个结论必须由用户显式把策略切成 allow 才能得到。
            return exec == ExecPolicy.ASK;
        }
        if (mode == PermissionMode.ACCEPT_EDITS) {
            return false;
        }
        if (mode == PermissionMode.ASK && isAskDangerous(toolName)) {
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
