package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * html_to_docx 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class HtmlToDocxParams extends ToolParams {

    @ToolParamSchema(description = "Word文档路径（.docx）", required = true)
    private String path;

    @ToolParamSchema(description = "HTML内容", required = true)
    private String htmlContent;

    @ToolParamSchema(description = "文档标题")
    private String title;

    @ToolParamSchema(description = "作者名称")
    private String author;

    @ToolParamSchema(description = "是否保留HTML样式", defaultValue = "true")
    private Boolean preserveStyles;

    /**
     * 是否保留HTML样式，默认true
     */
    public boolean isPreserveStylesOrDefault() {
        return preserveStyles != null ? preserveStyles : true;
    }
}