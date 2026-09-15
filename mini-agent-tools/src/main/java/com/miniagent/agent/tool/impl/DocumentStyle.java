package com.miniagent.agent.tool.impl;

import lombok.Data;

/**
 * 文档样式配置：支持LLM控制文档格式
 */
@Data
public class DocumentStyle {
    
    // ==================== 字体设置 ====================
    
    /** 字体名称 */
    private String fontFamily = "微软雅黑";
    
    /** 默认字体大小（磅） */
    private Double fontSize = 12.0;
    
    /** 标题字体大小（磅） */
    private Double titleFontSize = 24.0;
    
    /** 副标题字体大小（磅） */
    private Double subtitleFontSize = 18.0;
    
    /** 正文字体大小（磅） */
    private Double bodyFontSize = 12.0;
    
    // ==================== 颜色设置 ====================
    
    /** 文本颜色（十六进制，如 #000000） */
    private String textColor = "#000000";
    
    /** 标题颜色 */
    private String titleColor = "#000000";
    
    /** 强调文本颜色 */
    private String accentColor = "#0066CC";
    
    // ==================== 对齐设置 ====================
    
    /** 文本对齐方式：left/center/right/justify */
    private String alignment = "left";
    
    /** 标题对齐方式 */
    private String titleAlignment = "left";
    
    // ==================== 段落设置 ====================
    
    /** 行间距（倍数） */
    private Double lineSpacing = 1.5;
    
    /** 段前间距（磅） */
    private Double spaceBefore = 6.0;
    
    /** 段后间距（磅） */
    private Double spaceAfter = 6.0;
    
    /** 首行缩进（字符数） */
    private Double firstLineIndent = 0.0;
    
    // ==================== 边框和背景 ====================
    
    /** 是否显示边框 */
    private Boolean showBorder = false;
    
    /** 边框颜色 */
    private String borderColor = "#000000";
    
    /** 边框宽度（磅） */
    private Double borderWidth = 1.0;
    
    /** 背景颜色（十六进制，如 #FFFFFF） */
    private String backgroundColor = "#FFFFFF";
    
    // ==================== 表格设置 ====================
    
    /** 表格是否显示边框 */
    private Boolean tableShowBorder = true;
    
    /** 表格边框宽度 */
    private Double tableBorderWidth = 1.0;
    
    /** 表头是否加粗 */
    private Boolean tableHeaderBold = true;
    
    /** 表头背景颜色 */
    private String tableHeaderBackground = "#E6E6E6";
    
    // ==================== 列表设置 ====================
    
    /** 列表项前缀：bullet(•)/dash(-)/number(1.) */
    private String listPrefix = "bullet";
    
    /** 列表缩进（字符数） */
    private Double listIndent = 2.0;
    
    // ==================== 页面设置 ====================
    
    /** 页面宽度（英寸） */
    private Double pageWidth = 8.5;
    
    /** 页面高度（英寸） */
    private Double pageHeight = 11.0;
    
    /** 页边距（英寸） */
    private Double marginTop = 1.0;
    private Double marginBottom = 1.0;
    private Double marginLeft = 1.25;
    private Double marginRight = 1.25;
    
    // ==================== 工厂方法 ====================
    
    /**
     * 创建默认样式
     */
    public static DocumentStyle defaultStyle() {
        return new DocumentStyle();
    }
    
    /**
     * 创建简约样式
     */
    public static DocumentStyle minimalStyle() {
        DocumentStyle style = new DocumentStyle();
        style.setFontFamily("Arial");
        style.setFontSize(11.0);
        style.setTitleFontSize(20.0);
        style.setLineSpacing(1.2);
        style.setSpaceBefore(0.0);
        style.setSpaceAfter(6.0);
        return style;
    }
    
    /**
     * 创建商务样式
     */
    public static DocumentStyle businessStyle() {
        DocumentStyle style = new DocumentStyle();
        style.setFontFamily("微软雅黑");
        style.setFontSize(12.0);
        style.setTitleFontSize(28.0);
        style.setTitleColor("#1F4E79");
        style.setAccentColor("#2E75B6");
        style.setLineSpacing(1.5);
        return style;
    }
    
    /**
     * 创建学术样式
     */
    public static DocumentStyle academicStyle() {
        DocumentStyle style = new DocumentStyle();
        style.setFontFamily("Times New Roman");
        style.setFontSize(12.0);
        style.setTitleFontSize(16.0);
        style.setLineSpacing(2.0);
        style.setFirstLineIndent(2.0);
        style.setJustify(true);
        return style;
    }
    
    /**
     * 设置两端对齐
     */
    public void setJustify(boolean justify) {
        this.alignment = justify ? "justify" : "left";
    }
}