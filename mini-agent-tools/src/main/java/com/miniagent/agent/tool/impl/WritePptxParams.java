package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * write_pptx 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WritePptxParams extends ToolParams {

    @ToolParamSchema(description = "PowerPoint文件路径（.pptx）", required = true)
    private String path;

    @ToolParamSchema(description = "演示文稿标题", required = true)
    private String title;

    @ToolParamSchema(description = "幻灯片内容（JSON数组格式，每个元素为一张幻灯片）", required = true)
    private String slides;

    @ToolParamSchema(description = "作者名称")
    private String author;

    @ToolParamSchema(description = "幻灯片尺寸：wide(16:9) 或 standard(4:3)，默认wide")
    private String size;

    /**
     * 获取幻灯片尺寸，默认"wide"
     */
    public String getSizeOrDefault() {
        return size != null && !size.isEmpty() ? size : "wide";
    }
}