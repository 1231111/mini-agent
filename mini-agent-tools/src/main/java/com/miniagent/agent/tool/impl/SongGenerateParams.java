package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * song_generate 工具参数。
 *
 * <h2>为什么整个链路都要能在一次调用内结束，但不能超过闸门</h2>
 *
 * <p>SenseAudio 生歌是<b>异步</b>接口：提交只返回 {@code task_id}，歌要等约 3 分钟才出来。
 * 这里的做法是「在工具内部有界轮询」：轮询到 {@link #DEFAULT_POLL_BUDGET_SECONDS} 就
 * <b>正常返回</b> {@code status=PENDING} + {@code taskId}，由模型下一轮带上 {@code taskId} 续查。
 *
 * <p>关键是不能把等待拖到外层闸门：{@code song_generate} 既不是只读、也不幂等、
 * 也不是 {@code exec_command}，外层闸门一旦先触发，{@code AgentLoop.timeoutToolResult}
 * 会判成「终态未知」（{@code OUTCOME_UNKNOWN}）并<b>中止整轮任务</b>。
 * 所以外层闸门必须比内部预算宽，见 {@link #outerGateSeconds()}。
 *
 * <h2>为什么有 taskId / lyricsTaskId 两个续查字段</h2>
 *
 * <p>工具不能把任务状态藏在内存里（同一次用户消息可能换线程执行，重连还会重放）。
 * 把 {@code task_id} 显式回给模型、再由模型原样传回来，工具就是无状态的：
 * 歌词阶段超期回 {@code lyricsTaskId}，作曲阶段超期回 {@code taskId}，
 * 两个阶段都不会重做已经花掉的钱。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SongGenerateParams extends ToolParams {

    /**
     * 工具名。注册、超时策略、权限策略、媒体白名单、能力包五处都引用它，
     * 避免改名时漏掉一处 —— 漏掉超时那一处会直接让生歌在 60s 默认闸门被砍。
     */
    public static final String TOOL_NAME = "song_generate";

    /** 工具内部轮询上限（秒）。到点就返回 PENDING，绝不把等待拖到外层闸门。 */
    public static final int DEFAULT_POLL_BUDGET_SECONDS = 240;

    /**
     * 外层闸门相对内部预算要多留的秒数。
     *
     * <p>余量要覆盖「最后一次查询 + 下载整首歌 + 落盘」。外层若先触发，
     * 整个回合会被判成 {@code OUTCOME_UNKNOWN} 直接中止，见类注释。
     */
    private static final int OUTER_GATE_MARGIN_SECONDS = 30;

    @ToolParamSchema(
            description = "歌曲主题/风格描述，由服务端据此写词。"
                    + "例如「一首关于夏天海边的流行歌曲」「燃一点的国风摇滚」。"
                    + "prompt / lyrics / taskId 三者至少给一个")
    private String prompt;

    @ToolParamSchema(
            description = "直接指定歌词，支持 [verse] [chorus] [bridge] [outro] 段落标记"
                    + "（用分号分隔）。给了它就不再生成歌词")
    private String lyrics;

    @ToolParamSchema(
            description = "曲风，例如 pop / rock / jazz / folk / electronic / trap rap, dark, energetic。默认 pop")
    private String style;

    @ToolParamSchema(description = "歌名，可留空")
    private String title;

    @ToolParamSchema(
            description = "人声性别：f=女声（默认），m=男声。instrumental=true 时忽略")
    private String vocal;

    @ToolParamSchema(description = "true=纯音乐（无人声），默认 false。为 true 时不需要歌词")
    private Boolean instrumental;

    @ToolParamSchema(
            description = "续查已提交的歌曲任务：只查状态、不重新提交。"
                    + "上一轮返回 status=PENDING 时必须原样带回这里的 taskId")
    private String taskId;

    @ToolParamSchema(
            description = "续查已提交的歌词任务：只查状态、不重新提交。"
                    + "上一轮返回 status=PENDING 且 stage=lyrics 时用它继续")
    private String lyricsTaskId;

    /** 外层闸门应给的秒数（注册期静态值，供 {@code ToolConcurrencyPolicy} 与注册一起取用）。 */
    public static int outerGateSeconds() {
        return DEFAULT_POLL_BUDGET_SECONDS + OUTER_GATE_MARGIN_SECONDS;
    }
}
