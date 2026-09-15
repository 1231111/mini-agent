package com.miniagent.agent.tool;

import com.miniagent.agent.tool.impl.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 统一文档写入工具：根据文件扩展名自动选择处理方式
 * 支持：Word(.docx)、PowerPoint(.pptx)、Excel(.xlsx)、Markdown(.md)、HTML(.html)、纯文本(.txt)
 */
@Slf4j
@Component
public class WriteDocumentTool {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private WriteDocxTool writeDocxTool;

    @Autowired
    private WritePptxTool writePptxTool;

    @Autowired
    private WriteXlsxTool writeXlsxTool;

    @Autowired
    private HtmlToDocxTool htmlToDocxTool;

    @PostConstruct
    public void register() {
        toolRegistry.register(
                "write_document",
                "生成文档的首选工具：按目标文件扩展名自动选择处理方式，一次调用即可产出 Word / PPT / Excel / Markdown / HTML / 纯文本。\n" +
                "支持格式：.docx（标题、段落、列表、粗体斜体）、.pptx（幻灯片、标题、内容、备注）、\n" +
                ".xlsx（表头、数据行）、.md、.html、.txt。\n\n" +
                "什么时候用别的工具（只有这两种例外）：\n" +
                "- 需要 Word 页眉页脚 → 用 write_docx（本工具固定不加页眉页脚）\n" +
                "- 需要自定义工作表名、或关闭列宽自适应 → 用 write_xlsx\n" +
                "除此之外一律用本工具：Word / PPT / Excel / Markdown / HTML / 纯文本都由本工具一处产出，\n" +
                "不要去找别的写文档工具。\n\n" +
                "版式与样式：\n" +
                "- content 里写 HTML 标签、且目标为 .docx 时，会自动走 HTML 保版式转换；要精确控制版式就把 content 写成 HTML。\n" +
                "- 可选参数：style=default/minimal/business/academic，fontFamily、fontSize、titleFontSize、\n" +
                "  textColor、titleColor、alignment、lineSpacing、justify（仅 .docx 生效）。\n" +
                "- .pptx 可传 slideSize=wide/standard；.xlsx 可传 headers 指定表头行。\n\n" +
                "示例：\n" +
                "- Word 报告：path=\"report.docx\", content=\"# 报告标题\\n\\n正文...\"\n" +
                "- PPT：path=\"slides.pptx\", content=\"[{\\\"title\\\":\\\"标题\\\",\\\"content\\\":\\\"要点\\\"}]\"\n" +
                "- Excel：path=\"data.xlsx\", content=\"[[\\\"列1\\\",\\\"列2\\\"],[\\\"值1\\\",\\\"值2\\\"]]\"\n" +
                "- Markdown：path=\"doc.md\", content=\"# 标题\\n\\n正文\"",
                WriteDocumentParams.class,
                this::handle
        );
    }

    private String handle(WriteDocumentParams params) {
        try {
            String format = params.getFormatOrDefault();
            log.info("统一文档写入: 格式={}, 路径={}", format, params.getPath());
            
            // 根据格式分发到对应的工具
            return switch (format) {
                case "docx" -> writeDocx(params);
                case "pptx" -> writePptx(params);
                case "xlsx" -> writeXlsx(params);
                case "html" -> writeHtml(params);
                case "md", "markdown" -> writeMarkdown(params);
                case "txt" -> writeText(params);
                default -> {
                    String msg = String.format(
                            "{\"success\":false,\"error\":\"不支持的格式: %s。支持的格式: docx/pptx/xlsx/md/html/txt\"}",
                            format);
                    yield msg;
                }
            };
            
        } catch (Exception e) {
            log.error("统一文档写入失败", e);
            return "{\"success\":false,\"error\":\"文档写入失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 写入Word文档
     */
    private String writeDocx(WriteDocumentParams params) {
        WriteDocxParams docxParams = new WriteDocxParams();
        docxParams.setPath(params.getPath());
        docxParams.setTitle(params.getTitle());
        docxParams.setContent(params.getContent());
        docxParams.setAuthor(params.getAuthor());
        docxParams.setAddHeaderFooter(false);
        
        // 如果内容包含HTML标签，使用HTML转Word
        if (params.getContent() != null && params.getContent().contains("<")) {
            return writeHtmlToDocx(params);
        }
        
        return executeTool(() -> writeDocxTool.handle(docxParams));
    }

    /**
     * 写入PowerPoint
     */
    private String writePptx(WriteDocumentParams params) {
        WritePptxParams pptxParams = new WritePptxParams();
        pptxParams.setPath(params.getPath());
        pptxParams.setTitle(params.getTitle() != null ? params.getTitle() : "演示文稿");
        pptxParams.setSlides(params.getContent());
        pptxParams.setAuthor(params.getAuthor());
        pptxParams.setSize(params.getSlideSizeOrDefault());
        
        return executeTool(() -> writePptxTool.handle(pptxParams));
    }

    /**
     * 写入Excel
     */
    private String writeXlsx(WriteDocumentParams params) {
        WriteXlsxParams xlsxParams = new WriteXlsxParams();
        xlsxParams.setPath(params.getPath());
        xlsxParams.setSheetName("Sheet1");
        xlsxParams.setData(params.getContent());
        xlsxParams.setHeaders(params.getHeaders());
        xlsxParams.setAutoSizeColumns(true);
        
        return executeTool(() -> writeXlsxTool.handle(xlsxParams));
    }

    /**
     * 写入HTML文件
     */
    private String writeHtml(WriteDocumentParams params) {
        // HTML文件直接作为文本写入
        return writeTextFile(params.getPath(), params.getContent(), "html");
    }

    /**
     * 写入Markdown文件
     */
    private String writeMarkdown(WriteDocumentParams params) {
        // Markdown文件直接作为文本写入
        return writeTextFile(params.getPath(), params.getContent(), "md");
    }

    /**
     * 写入纯文本文件
     */
    private String writeText(WriteDocumentParams params) {
        // 纯文本文件直接写入
        return writeTextFile(params.getPath(), params.getContent(), "txt");
    }

    /**
     * HTML转Word
     */
    private String writeHtmlToDocx(WriteDocumentParams params) {
        HtmlToDocxParams htmlParams = new HtmlToDocxParams();
        htmlParams.setPath(params.getPath());
        htmlParams.setHtmlContent(params.getContent());
        htmlParams.setTitle(params.getTitle());
        htmlParams.setAuthor(params.getAuthor());
        htmlParams.setPreserveStyles(params.isPreserveStylesOrDefault());
        
        return executeTool(() -> htmlToDocxTool.handle(htmlParams));
    }

    /**
     * 写入文本文件
     */
    private String writeTextFile(String path, String content, String extension) {
        try {
            java.nio.file.Path targetPath = resolvePath(path);
            java.nio.file.Files.createDirectories(targetPath.getParent());
            java.nio.file.Files.writeString(targetPath, content, java.nio.charset.StandardCharsets.UTF_8);
            
            long fileSize = targetPath.toFile().length();
            return String.format(
                    "{\"success\":true,\"path\":\"%s\",\"size\":%d,\"format\":\"%s\",\"message\":\"文件写入成功\"}",
                    targetPath.toString().replace("\\", "/"),
                    fileSize,
                    extension
            );
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"文件写入失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 执行工具并捕获异常
     */
    private String executeTool(java.util.function.Supplier<String> toolExecutor) {
        try {
            return toolExecutor.get();
        } catch (Exception e) {
            return "{\"success\":false,\"error\":\"工具执行失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 解析文件路径
     */
    private java.nio.file.Path resolvePath(String path) {
        return BuiltinTools.resolveOutputPath(path);
    }
}