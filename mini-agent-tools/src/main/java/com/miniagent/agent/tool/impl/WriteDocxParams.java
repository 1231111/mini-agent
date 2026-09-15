package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * write_docx 工具参数
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WriteDocxParams extends ToolParams {

    @ToolParamSchema(description = "Word文档路径（.docx）", required = true)
    private String path;

    @ToolParamSchema(description = "文档标题")
    private String title;

    @ToolParamSchema(description = "文档内容（支持Markdown格式）", required = true)
    private String content;

    @ToolParamSchema(description = "作者名称")
    private String author;

    @ToolParamSchema(description = "是否添加页眉页脚", defaultValue = "false")
    private Boolean addHeaderFooter;

    // ==================== 样式控制参数 ====================
    
    @ToolParamSchema(description = "预设样式：default/minimal/business/academic（可选，默认default）")
    private String style;

    @ToolParamSchema(description = "字体名称（可选，如：微软雅黑/Arial/Times New Roman）")
    private String fontFamily;

    @ToolParamSchema(description = "字体大小（可选，单位：磅）")
    private Double fontSize;

    @ToolParamSchema(description = "标题字体大小（可选）")
    private Double titleFontSize;

    @ToolParamSchema(description = "文本颜色（可选，十六进制如：#000000）")
    private String textColor;

    @ToolParamSchema(description = "标题颜色（可选）")
    private String titleColor;

    @ToolParamSchema(description = "对齐方式：left/center/right/justify（可选）")
    private String alignment;

    @ToolParamSchema(description = "行间距（可选，倍数如：1.5）")
    private Double lineSpacing;

    @ToolParamSchema(description = "是否两端对齐（可选）")
    private Boolean justify;

    /**
     * 是否添加页眉页脚，默认false
     */
    public boolean isAddHeaderFooterOrDefault() {
        return addHeaderFooter != null ? addHeaderFooter : false;
    }

    /**
     * 获取文档样式配置
     */
    public DocumentStyle getDocumentStyle() {
        DocumentStyle styleObj;
        
        // 根据预设样式创建基础样式
        if (style != null && !style.isEmpty()) {
            switch (style.toLowerCase()) {
                case "minimal":
                    styleObj = DocumentStyle.minimalStyle();
                    break;
                case "business":
                    styleObj = DocumentStyle.businessStyle();
                    break;
                case "academic":
                    styleObj = DocumentStyle.academicStyle();
                    break;
                default:
                    styleObj = DocumentStyle.defaultStyle();
            }
        } else {
            styleObj = DocumentStyle.defaultStyle();
        }
        
        // 应用用户自定义的样式参数（覆盖预设值）
        if (fontFamily != null && !fontFamily.isEmpty()) {
            styleObj.setFontFamily(fontFamily);
        }
        if (fontSize != null) {
            styleObj.setFontSize(fontSize);
            styleObj.setBodyFontSize(fontSize);
        }
        if (titleFontSize != null) {
            styleObj.setTitleFontSize(titleFontSize);
        }
        if (textColor != null && !textColor.isEmpty()) {
            styleObj.setTextColor(textColor);
        }
        if (titleColor != null && !titleColor.isEmpty()) {
            styleObj.setTitleColor(titleColor);
        }
        if (alignment != null && !alignment.isEmpty()) {
            styleObj.setAlignment(alignment);
        }
        if (lineSpacing != null) {
            styleObj.setLineSpacing(lineSpacing);
        }
        if (justify != null) {
            styleObj.setJustify(justify);
        }
        
        return styleObj;
    }
}