package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ShellCommandLine;
import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * exec_command 工具参数。
 *
 * <h2>为什么有 timeout</h2>
 *
 * <p>执行时长完全取决于跑什么：{@code git status} 几十毫秒，{@code mvnw package} 几分钟。
 * 固定 30 秒的旧实现让构建、测试、依赖安装这类命令必然超时，而超时在 {@code exec_command}
 * 上会升级成「终态未知」并中止整个 Agent 循环 —— 一条构建命令能把整轮任务打死。
 * 现在把超时交给调用方声明，并给出上下限。</p>
 *
 * <h2>为什么有 description</h2>
 *
 * <p>{@code exec_command} 首次调用需要用户在会话里批准。审批卡片只能显示原始命令行
 * （如 {@code find . -name "*.tmp" -exec rm {} \; }），用户看不懂它在干什么。
 * 这个字段是给审批界面用的人话说明。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ExecCommandParams extends ToolParams {

    /** 未声明超时时的默认值（秒）。 */
    public static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /** 调用方能声明的上限（秒）。与 Claude Code 的 BASH_MAX_TIMEOUT_MS 同量级。 */
    public static final int MAX_TIMEOUT_SECONDS = 600;

    /**
     * 外层闸门相对工具自身预算要多留的秒数。
     *
     * <p>外层闸门（{@code AgentLoop} 的 future.get）一旦先触发，超时会被判成「终态未知」并中止整轮；
     * 而工具自己处理超时是「强杀进程 + 返回部分输出」，是可控终态。所以外层必须比内层宽，
     * 否则内部那套精细处理永远是死代码 —— 这正是改造前 30s 内外相同的后果。</p>
     */
    public static final int OUTER_GATE_MARGIN_SECONDS = 15;

    @ToolParamSchema(description = "要执行的命令", required = true)
    private String command;

    @ToolParamSchema(
            description = "超时秒数。默认 " + ExecCommandParams.DEFAULT_TIMEOUT_SECONDS
                    + "，上限 " + ExecCommandParams.MAX_TIMEOUT_SECONDS
                    + "。构建/测试/安装这类慢命令请显式调大；交互式命令请勿执行",
            defaultValue = "" + ExecCommandParams.DEFAULT_TIMEOUT_SECONDS)
    private Integer timeout;

    @ToolParamSchema(description = "这条命令在做什么（人话，5-15 字的动词短语）。"
            + "用于把审批提示和过程说明展示给用户，例如“查看工作区状态”“编译并运行测试”。"
            + "不要写“复杂”“有风险”这类评价词，只描述它做什么")
    private String description;

    /** 本次声明的超时（已夹到上下限）。 */
    public int requestedTimeoutSeconds() {
        int value = timeout == null ? DEFAULT_TIMEOUT_SECONDS : timeout;
        return Math.min(Math.max(1, value), MAX_TIMEOUT_SECONDS);
    }

    /**
     * 外层闸门应给的秒数 = 工具自身预算 + 余量。
     * 供 {@code ToolRegistry} 注册自适应超时、{@code AgentLoop} 取闸门值使用。
     */
    public int outerGateSeconds() {
        return requestedTimeoutSeconds() + OUTER_GATE_MARGIN_SECONDS;
    }

    /** 未声明超时时，整条链路的默认闸门值（注册期静态兜底，与自适应值同源，避免漂移）。 */
    public static int defaultOuterGateSeconds() {
        return DEFAULT_TIMEOUT_SECONDS + OUTER_GATE_MARGIN_SECONDS;
    }

    /**
     * 从原始参数 JSON 求内层预算秒数；参数缺失或解析失败一律回退 {@link #DEFAULT_TIMEOUT_SECONDS}。
     *
     * <p>供 {@code ToolConcurrencyPolicy.profileOf(name, args)} 算随调用变化的闸门用。
     * 回退到默认值而不是 0：闸门取到 0 会让 {@code future.get(0)} 立刻超时。</p>
     */
    public static long requestedTimeoutSecondsOf(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        try {
            return fromJson(argumentsJson, ExecCommandParams.class).requestedTimeoutSeconds();
        } catch (Exception e) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
    }

    /**
     * 从原始参数 JSON 求外层闸门秒数；解析不出来返回 0，由调用方回退到静态值。
     *
     * <p>注册期把方法引用挂到 {@code Tool.adaptiveTimeoutSeconds} 上，这样超时随调用走，
     * 而不是在注册时被钉死成一个常量。</p>
     */
    public static long outerGateSecondsOf(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return defaultOuterGateSeconds();
        }
        try {
            return fromJson(argumentsJson, ExecCommandParams.class).outerGateSeconds();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 给审批提示与过程说明用的人话文本。
     *
     * <p>与 {@code AskUserQuestionTool.displayText} 同一路数：显示文本的职责放在工具自己身上，
     * 调用方只问「这条调用该怎么展示给用户看」。</p>
     */
    public static String displayText(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return "执行命令";
        }
        ExecCommandParams params;
        try {
            params = fromJson(argumentsJson, ExecCommandParams.class);
        } catch (Exception e) {
            return "执行命令";
        }
        String command = params.getCommand() == null ? "" : params.getCommand().trim();
        String desc = params.getDescription() == null ? "" : params.getDescription().trim();
        String singleLine = command.replaceAll("\\s+", " ");
        if (singleLine.length() > 80) {
            singleLine = singleLine.substring(0, 80) + "…";
        }
        if (desc.isEmpty()) {
            return singleLine.isEmpty() ? "执行命令" : singleLine;
        }
        return desc + "（" + singleLine + "）";
    }

    /** 基础命令名，供审计与日志使用（不含管道后续命令）。 */
    public String baseCommand() {
        ShellCommandLine.Segment first = ShellCommandLine.segments(command).stream()
                .findFirst().orElse(null);
        return first == null ? "" : first.baseCommand();
    }
}
