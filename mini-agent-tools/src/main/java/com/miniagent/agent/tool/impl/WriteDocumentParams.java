package com.miniagent.agent.tool.impl;

import com.miniagent.agent.tool.ToolParamSchema;
import com.miniagent.agent.tool.ToolParams;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * write_document 统一文档写入工具参数
 * 根据文件扩展名自动选择处理方式
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WriteDocumentParams extends ToolParams {

    @ToolParamSchema(description = "文件路径（支持 .docx/.pptx/.xlsx/.md/.html/.txt 等）", required = true)
    private String path;

    @ToolParamSchema(description = "文档标题（可选）")
    private String title;

    @ToolParamSchema(description = "文档内容（支持Markdown/HTML/纯文本）", required = true)
    private String content;

    @ToolParamSchema(description = "作者名称（可选）")
    private String author;

    @ToolParamSchema(description = "文档格式：docx/pptx/xlsx/md/html/txt（可选，默认根据扩展名自动识别）")
    private String format;

    @ToolParamSchema(description = "是否保留HTML样式（仅HTML转Word时有效）", defaultValue = "true")
    private Boolean preserveStyles;

    @ToolParamSchema(description = "幻灯片尺寸（仅PPT时有效）：wide(16:9)/standard(4:3)，默认wide")
    private String slideSize;

    @ToolParamSchema(description = "Excel表头行（仅Excel时有效，JSON数组格式）")
    private String headers;

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
     * 获取文档格式，如果没有指定则根据扩展名自动识别
     */
    public String getFormatOrDefault() {
        if (format != null && !format.isEmpty()) {
            return format.toLowerCase();
        }
        return detectFormatFromPath(path);
    }

    /**
     * 根据文件扩展名检测格式
     */
    private String detectFormatFromPath(String path) {
        if (path == null) {
            return "txt";
        }
        
        String lower = path.toLowerCase();
        if (lower.endsWith(".docx")) {
            return "docx";
        } else if (lower.endsWith(".pptx")) {
            return "pptx";
        } else if (lower.endsWith(".xlsx")) {
            return "xlsx";
        } else if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "html";
        } else if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return "md";
        } else if (lower.endsWith(".txt")) {
            return "txt";
        } else {
            return "txt"; // 默认文本格式
        }
    }

    /**
     * 是否保留HTML样式，默认true
     */
    public boolean isPreserveStylesOrDefault() {
        return preserveStyles != null ? preserveStyles : true;
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

    /**
     * 获取幻灯片尺寸，默认"wide"
     */
    public String getSlideSizeOrDefault() {
        return slideSize != null && !slideSize.isEmpty() ? slideSize : "wide";
    }
}