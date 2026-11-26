package com.miniagent.agent.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.impl.WritePptxParams;
import com.miniagent.common.io.AtomicFiles;
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
            DocumentResourceBudget.requireText(params.getTitle(), "PowerPoint title");
            DocumentResourceBudget.requireText(params.getAuthor(), "PowerPoint author");
            DocumentResourceBudget.requireText(params.getSlides(), "PowerPoint slides JSON");
            String size = params.getSizeOrDefault();
            if (!"wide".equalsIgnoreCase(size) && !"standard".equalsIgnoreCase(size))
                throw new IllegalArgumentException("PowerPoint size 只支持 wide/standard");
            List<Map<String, Object>> slides = params.getSlides() == null || params.getSlides().isBlank() ? List.of()
                    : MAPPER.readValue(params.getSlides(), new TypeReference<List<Map<String, Object>>>() {});
            validateSlides(slides);
            // 解析路径
            Path targetPath = resolvePptxPath(params.getPath());
            
            // 创建PowerPoint演示文稿
            try (XMLSlideShow presentation = new XMLSlideShow()) {
            if (params.getTitle() != null) presentation.getProperties().getCoreProperties().setTitle(params.getTitle());
            if (params.getAuthor() != null) presentation.getProperties().getCoreProperties().setCreator(params.getAuthor());
            
            // 设置幻灯片尺寸
            if ("wide".equalsIgnoreCase(params.getSizeOrDefault())) {
                presentation.setPageSize(new java.awt.Dimension(960, 540)); // POI dimensions are points, 16:9
            } else {
                presentation.setPageSize(new java.awt.Dimension(720, 540)); // 4:3
            }
            
            // 解析幻灯片内容
            if (!slides.isEmpty()) {
                
                // 创建幻灯片
                for (Map<String, Object> slideData : slides) {
                    DocumentResourceBudget.requireActive();
                    createSlide(presentation, slideData);
                }
            }
            
            // 保存演示文稿
            DocumentResourceBudget.write(targetPath, presentation::write);
            
            long fileSize = targetPath.toFile().length();
            log.info("PowerPoint演示文稿已创建: {} ({} 字节)", targetPath.toAbsolutePath(), fileSize);
            
            return String.format(
                    "{\"success\":true,\"path\":\"%s\",\"size\":%d,\"slides\":%d,\"message\":\"PowerPoint演示文稿创建成功\"}",
                    BuiltinTools.jsonEscape(targetPath.toString().replace("\\", "/")),
                    fileSize,
                    presentation.getSlides().size()
            );
            }
            
        } catch (Exception e) {
            log.error("创建PowerPoint演示文稿失败", e);
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "创建PowerPoint演示文稿失败: " + e.getMessage(), false).legacyText();
        }
    }

    private static void validateSlides(List<Map<String, Object>> slides) {
        if (slides == null) throw new IllegalArgumentException("PowerPoint slides 必须是数组，不能为 null");
        if (slides.size() > DocumentResourceBudget.MAX_SLIDES)
            throw new IllegalArgumentException("PowerPoint 幻灯片数超过上限 " + DocumentResourceBudget.MAX_SLIDES);
        long chars = 0, blocks = 0;
        for (Map<String, Object> slide : slides) {
            DocumentResourceBudget.requireActive();
            if (slide == null) throw new IllegalArgumentException("PowerPoint 每张幻灯片必须是对象，不能为 null");
            Object layout = slide.get("layout");
            if (layout != null && !"title_and_content".equals(layout))
                throw new IllegalArgumentException("PowerPoint 当前只支持 title_and_content 布局");
            for (String field : List.of("title", "notes")) {
                Object value = slide.get(field);
                if (value != null && !(value instanceof String)) throw new IllegalArgumentException("PowerPoint " + field + " 必须是字符串");
                if (value instanceof String text) { chars += text.length(); blocks += text.lines().count(); }
            }
            Object content = slide.get("content");
            if (content instanceof String text) { chars += text.length(); blocks += text.lines().count(); }
            else if (content instanceof List<?> items) {
                if (items.size() > DocumentResourceBudget.MAX_SLIDE_ITEMS)
                    throw new IllegalArgumentException("PowerPoint 单页条目数超过上限 " + DocumentResourceBudget.MAX_SLIDE_ITEMS);
                for (Object item : items) {
                    if (!(item instanceof String text)) throw new IllegalArgumentException("PowerPoint 列表条目必须是字符串");
                    chars += text.length(); blocks += Math.max(1L, text.lines().count());
                }
            } else if (content != null) throw new IllegalArgumentException("PowerPoint content 必须是字符串或字符串数组");
            if (chars > DocumentResourceBudget.MAX_CONTENT_CHARS || blocks > DocumentResourceBudget.MAX_BLOCKS)
                throw new IllegalArgumentException("PowerPoint 文本或段落数量超过文档安全预算");
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
            if (content instanceof String && !((String) content).isEmpty()) {
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
            java.awt.geom.Rectangle2D anchor = new java.awt.geom.Rectangle2D.Double(36, 30,
                    slide.getSlideShow().getPageSize().getWidth() - 72, 70);
            titleShape = slide.createTextBox();
            titleShape.setAnchor(anchor);
            titleShape.setText(title);
        }
        
        // 设置标题样式
        for (XSLFTextParagraph paragraph : titleShape.getTextParagraphs())
            for (XSLFTextRun run : paragraph.getTextRuns()) {
                run.setBold(true);
                run.setFontSize(44.0);
            }
        
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
            java.awt.geom.Rectangle2D anchor = new java.awt.geom.Rectangle2D.Double(36, 120,
                    slide.getSlideShow().getPageSize().getWidth() - 72,
                    slide.getSlideShow().getPageSize().getHeight() - 156);
            contentShape = slide.createTextBox();
            contentShape.setAnchor(anchor);
            contentShape.setText(content);
        }
        
        // 设置内容样式
        for (XSLFTextParagraph paragraph : contentShape.getTextParagraphs())
            for (XSLFTextRun run : paragraph.getTextRuns()) run.setFontSize(24.0);
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
            java.awt.geom.Rectangle2D anchor = new java.awt.geom.Rectangle2D.Double(36, 120,
                    slide.getSlideShow().getPageSize().getWidth() - 72,
                    slide.getSlideShow().getPageSize().getHeight() - 156);
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
        XSLFNotes notesShape = slide.getSlideShow().getNotesSlide(slide);
        for (XSLFShape shape : notesShape.getShapes()) {
            if (shape instanceof XSLFTextShape text && text.getTextType() == org.apache.poi.sl.usermodel.Placeholder.BODY) {
                text.setText(notes);
                return;
            }
        }
        notesShape.createTextBox().setText(notes);
    }

    /**
     * 解析PowerPoint文件路径
     */
    private Path resolvePptxPath(String path) {
        return BuiltinTools.resolveOutputPath(path);
    }
}
