package com.miniagent.common.permission;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

/**
 * 终端命令执行（{@code exec_command}）的策略档位。
 *
 * <p><b>为什么不是 boolean。</b>原来只有一个 {@code agent.tools.exec-enabled}，
 * 而且它是 {@code ToolPipeline} 构造器注入的 {@code final boolean} —— 启动之后改不了。
 * 但出厂形态需要"默认可用"（客户机上得能跑命令），用户又需要能随时收紧，
 * 所以拆成三档，并允许在运行期按会话覆盖：
 *
 * <ul>
 *   <li>{@link #BLOCK} <b>禁止</b> —— 硬闸门：直接拒绝执行，连批准的机会都没有。
 *       刻意不受 {@code PermissionMode.ACCEPT_EDITS} 影响 ——
 *       "自动编辑"的含义是"别问我"，不是"我允许你执行命令"。</li>
 *   <li>{@link #ASK} <b>需批准</b> —— 本会话内弹一次确认，批准后记住
 *       （复用 {@code SessionPermissionStore.askGrantedTools}）。</li>
 *   <li>{@link #ALLOW} <b>放行</b> —— 免批。</li>
 * </ul>
 *
 * <p><b>生效值</b> = 会话覆盖 ?? 全局默认（{@code agent.tools.exec-policy}）。
 *
 * <p><b>为什么放在 common 而不是 loop：</b>{@code BuiltinTools}（mini-agent-tools）
 * 注册工具时也要按它打日志，而 tools 依赖 common、<b>不</b>依赖 loop
 * （loop 依赖 tools，反向依赖会成环）。这个枚举没有别的依赖，放 common 最合适。
 */
public enum ExecPolicy {

    BLOCK("block", "禁止"),
    ASK("ask", "需批准"),
    ALLOW("allow", "放行");

    private final String wireName;
    private final String labelZh;

    ExecPolicy(String wireName, String labelZh) {
        this.wireName = wireName;
        this.labelZh = labelZh;
    }

    public String wireName() {
        return wireName;
    }

    public String labelZh() {
        return labelZh;
    }

    /**
     * 解析运行期传入的值（配置项 / API 请求体）。
     *
     * <p><b>刻意无法识别时返回 {@code null}，而不是兜底成某一档。</b>
     * 配置写错要在启动时就炸出来、API 传错要能回 400 ——
     * 静默兜底会让"我明明配了 block，怎么还在执行"这类问题极难查。
     */
    public static ExecPolicy parse(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "block", "deny", "denied", "forbid", "forbidden", "off" -> BLOCK;
            case "ask", "approve", "confirm", "ask_first" -> ASK;
            case "allow", "auto", "free", "on", "yolo" -> ALLOW;
            default -> null;
        };
    }

    /**
     * 兼容已弃用的 boolean 开关。
     *
     * <p><b>注意 {@code false} 映射成 {@link #ASK} 而不是 {@link #BLOCK}</b> ——
     * 旧语义里 {@code exec-enabled=false} 表示"默认需会话批准"，而不是"禁止执行"
     * （{@code PermissionPolicy.needsSessionGrant} 当时返回 true，即弹批准）。
     * 映射成 BLOCK 会让存量配置的行为悄悄变严，属于不可接受的静默变更。
     */
    public static ExecPolicy fromLegacyExecEnabled(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s)) {
            return ALLOW;
        }
        if ("false".equals(s)) {
            return ASK;
        }
        return null;
    }

    /** 是否可在会话里被覆盖成"更严"的档位（供前端决定要不要提示）。 */
    public boolean isBlocking() {
        return this == BLOCK;
    }
}
