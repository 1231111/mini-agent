package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * minimax_music 参数。MiniMax 音乐生成是同步接口，一次调用内出歌。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MiniMaxMusicParams extends ToolParams {

    public static final String TOOL_NAME = "minimax_music";

    /** 内层预算：写词（若需要）+ 作曲，须在外层闸门之前返回。 */
    public static final int BUDGET_SECONDS = 200;

    public static final int OUTER_GATE_MARGIN_SECONDS = 30;

    @ToolParamSchema(
            description = "风格/主题描述，例如「温暖的中文流行抒情，女声，钢琴吉他，BPM 72」。"
                    + "纯音乐时必填")
    private String prompt;

    @ToolParamSchema(
            description = "歌词，用换行分隔，可用 [Verse] [Chorus] [Bridge] [Outro]。"
                    + "不传且 instrumental=false 时由 MiniMax 按 prompt 写词")
    private String lyrics;

    @ToolParamSchema(description = "歌名，可留空")
    private String title;

    @ToolParamSchema(description = "true=纯音乐无人声，默认 false")
    private Boolean instrumental;
}
