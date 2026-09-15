package com.miniagent.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.impl.WritePptxParams;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xslf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * PowerPoint 写入实现：由 write_document 按目标扩展名分派调用，本身不作为独立工具注册。
 * 它的参数与能力完全被 write_document 覆盖，对外只保留 write_document 一个入口。
 */
@Slf4j
@Component
public class WritePptxTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String handle(WritePptxParams params) {
        try {
            // 解析路径
            Path targetPath = resolvePptxPath(params.getPath());
            
            // 创建PowerPoint演示文稿
            XMLSlideShow presentation = new XMLSlideShow();
            
            // 设置幻灯片尺寸
            if ("wide".equalsIgnoreCase(params.getSizeOrDefault())) {
                presentation.setPageSize(new java.awt.Dimension(12192, 6858)); // 16:9
            } else {
                presentation.setPageSize(new java.awt.Dimension(9144, 6858)); // 4:3
            }
            
            // 解析幻灯片内容
            String slidesJson = params.getSlides();
            if (slidesJson != null && !slidesJson.isEmpty()) {
                List<Map<String, Object>> slides = MAPPER.readValue(
                        slidesJson, new TypeReference<List<Map<String, Object>>>() {});
                
                // 创建幻灯片
                for (Map<String, Object> slideData : slides) {
                    createSlide(presentation, slideData);
                }
            }
            
            // 保存演示文稿
            try (FileOutputStream out = new FileOutputStream(targetPath.toFile())) {
                presentation.write(out);
            }
            
            long fileSize = targetPath.toFile().length();
            log.info("PowerPoint演示文稿已创建: {} ({} 字节)", targetPath.toAbsolutePath(), fileSize);
            
            return String.format(
                    "{\"success\":true,\"path\":\"%s\",\"size\":%d,\"slides\":%d,\"message\":\"PowerPoint演示文稿创建成功\"}",
                    targetPath.toString().replace("\\", "/"),
                    fileSize,
                    presentation.getSlides().size()
            );
            
        } catch (Exception e) {
            log.error("创建PowerPoint演示文稿失败", e);
            return "{\"success\":false,\"error\":\"创建PowerPoint演示文稿失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 创建幻灯片
     */
    private void createSlide(XMLSlideShow presentation, Map<String, Object> slideData) {
        // 创建空白幻灯片
        XSLFSlide slide = presentation.createSlide();
        
        // 获取幻灯片布局
        String layout = (String) slideData.getOrDefault("layout", "title_and_content");
        
        // 创建标题
        String title = (String) slideData.get("title");
        if (title != null && !title.isEmpty()) {
            XSLFShape titleShape = createTitleShape(slide, title);
        }
        
        // 创建内容
        Object content = slideData.get("content");
        if (content != null) {
            if (content instanceof String) {
                // 文本内容
                createTextContent(slide, (String) content);
            } else if (content instanceof List) {
                // 列表内容
                createListContent(slide, (List<?>) content);
            }
        }
        
        // 创建备注
        String notes = (String) slideData.get("notes");
        if (notes != null && !notes.isEmpty()) {
            createNotes(slide, notes);
        }
    }

    /**
     * 创建标题形状
     */
    private XSLFShape createTitleShape(XSLFSlide slide, String title) {
        // 创建标题文本框（使用占位符或手动创建）
        XSLFTextShape titleShape = null;
        
        // 尝试使用占位符
        try {
            titleShape = (XSLFTextShape) slide.getPlaceholder(0); // 0通常是标题占位符
            if (titleShape != null) {
                titleShape.setText(title);
            }
        } catch (Exception e) {
            // 如果占位符不存在，手动创建
            titleShape = null;
        }
        
        // 如果占位符不存在，手动创建文本框
        if (titleShape == null) {
            java.awt.Rectangle anchor = new java.awt.Rectangle(50, 50, 900, 100);
            titleShape = slide.createTextBox();
            titleShape.setAnchor(anchor);
            titleShape.setText(title);
        }
        
        // 设置标题样式
        XSLFTextParagraph paragraph = titleShape.getTextParagraphs().get(0);
        // PowerPoint中设置对齐方式 - 使用段落属性
        // 注意：XSLFTextParagraph可能没有setAlign方法，这里简化处理
        
        XSLFTextRun run = paragraph.getTextRuns().get(0);
        run.setBold(true);
        run.setFontSize(44.0);
        
        return titleShape;
    }

    /**
     * 创建文本内容
     */
    private void createTextContent(XSLFSlide slide, String content) {
        // 创建内容文本框（使用占位符或手动创建）
        XSLFTextShape contentShape = null;
        
        // 尝试使用占位符（通常是第1个或第2个占位符）
        try {
            contentShape = (XSLFTextShape) slide.getPlaceholder(1); // 1通常是内容占位符
            if (contentShape != null) {
                contentShape.setText(content);
            }
        } catch (Exception e) {
            // 如果占位符不存在，手动创建
            contentShape = null;
        }
        
        // 如果占位符不存在，手动创建文本框
        if (contentShape == null) {
            java.awt.Rectangle anchor = new java.awt.Rectangle(50, 180, 900, 400);
            contentShape = slide.createTextBox();
            contentShape.setAnchor(anchor);
            contentShape.setText(content);
        }
        
        // 设置内容样式
        XSLFTextParagraph paragraph = contentShape.getTextParagraphs().get(0);
        // PowerPoint中设置对齐方式 - 使用段落属性
        // 注意：XSLFTextParagraph可能没有setAlign方法，这里简化处理
        
        XSLFTextRun run = paragraph.getTextRuns().get(0);
        run.setFontSize(24.0);
    }

    /**
     * 创建列表内容
     */
    private void createListContent(XSLFSlide slide, List<?> items) {
        // 创建内容文本框（使用占位符或手动创建）
        XSLFTextShape contentShape = null;
        
        // 尝试使用占位符
        try {
            contentShape = (XSLFTextShape) slide.getPlaceholder(1); // 1通常是内容占位符
        } catch (Exception e) {
            contentShape = null;
        }
        
        // 如果占位符不存在，手动创建文本框
        if (contentShape == null) {
            java.awt.Rectangle anchor = new java.awt.Rectangle(50, 180, 900, 400);
            contentShape = slide.createTextBox();
            contentShape.setAnchor(anchor);
        }
        
        // 添加列表内容
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            String text = item.toString();
            
            if (i == 0) {
                // 第一行直接设置
                contentShape.setText("• " + text);
            } else {
                // 后续行添加新段落
                XSLFTextParagraph paragraph = contentShape.addNewTextParagraph();
                XSLFTextRun run = paragraph.addNewTextRun();
                run.setText("• " + text);
                run.setFontSize(24.0);
            }
        }
    }

    /**
     * 创建备注
     */
    private void createNotes(XSLFSlide slide, String notes) {
        XSLFNotes notesShape = slide.getNotes();
        if (notesShape != null) {
            // 获取备注的文本段落
            List<List<XSLFTextParagraph>> paragraphs = notesShape.getTextParagraphs();
            if (paragraphs != null && !paragraphs.isEmpty() && !paragraphs.get(0).isEmpty()) {
                // 修改第一个段落的文本
                XSLFTextParagraph paragraph = paragraphs.get(0).get(0);
                List<XSLFTextRun> runs = paragraph.getTextRuns();
                if (runs != null && !runs.isEmpty()) {
                    runs.get(0).setText(notes);
                }
            }
        }
    }

    /**
     * 解析PowerPoint文件路径
     */
    private Path resolvePptxPath(String path) {
        return BuiltinTools.resolveOutputPath(path);
    }
}