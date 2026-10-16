package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * lyria_music 参数。Lyria 3.5 经 Gemini Interactions API 同步出歌。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LyriaMusicParams extends ToolParams {

    public static final String TOOL_NAME = "lyria_music";

    /** 内层预算。HTTP 超时必须短于它，避免外层闸门先杀请求。 */
    public static final int BUDGET_SECONDS = 250;

    public static final int OUTER_GATE_MARGIN_SECONDS = 30;

    @ToolParamSchema(
            description = "风格/主题，可用英文或中文，例如「2 分钟中文流行，女声，钢琴吉他，打工人下班」。"
                    + "可在文案里写时长")
    private String prompt;

    @ToolParamSchema(
            description = "可选歌词。给出后会拼进 prompt，由 Lyria 按这段词演唱")
    private String lyrics;

    @ToolParamSchema(description = "true=只要器乐、不要人声，默认 false")
    private Boolean instrumental;
}
