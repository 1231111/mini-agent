package com.miniagent.agent.tool;

import com.miniagent.agent.tool.impl.HtmlToDocxParams;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 转 Word 实现：由 write_document 在「目标是 .docx 且内容含 HTML」时自动分派调用，
 * 本身不作为独立工具注册——它的能力完全被 write_document 覆盖。
 */
@Slf4j
@Component
public class HtmlToDocxTool {

    public String handle(HtmlToDocxParams params) {
        try {
            // 解析路径
            Path targetPath = resolveDocxPath(params.getPath());
            
            // 创建Word文档
            XWPFDocument document = new XWPFDocument();
            
            // 设置文档属性
            if (params.getTitle() != null && !params.getTitle().isEmpty()) {
                document.getProperties().getCoreProperties().setTitle(params.getTitle());
            }
            if (params.getAuthor() != null && !params.getAuthor().isEmpty()) {
                document.getProperties().getCoreProperties().setCreator(params.getAuthor());
            }
            
            // 解析HTML并添加到文档
            String htmlContent = params.getHtmlContent();
            if (htmlContent != null && !htmlContent.isEmpty()) {
                parseHtmlToDocument(document, htmlContent, params.isPreserveStylesOrDefault());
            }
            
            // 保存文档
            try (FileOutputStream out = new FileOutputStream(targetPath.toFile())) {
                document.write(out);
            }
            
            document.close();
            
            long fileSize = targetPath.toFile().length();
            log.info("HTML已转换为Word文档: {} ({} 字节)", targetPath.toAbsolutePath(), fileSize);
            
            return String.format(
                    "{\"success\":true,\"path\":\"%s\",\"size\":%d,\"message\":\"HTML已成功转换为Word文档\"}",
                    targetPath.toString().replace("\\", "/"),
                    fileSize
            );
            
        } catch (Exception e) {
            log.error("HTML转Word文档失败", e);
            return "{\"success\":false,\"error\":\"HTML转Word文档失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 解析HTML并添加到Word文档
     */
    private void parseHtmlToDocument(XWPFDocument document, String html, boolean preserveStyles) {
        // 移除HTML标签，保留文本内容
        String text = html;
        
        // 处理标题
        text = processHeadings(document, text);
        
        // 处理段落
        text = processParagraphs(document, text);
        
        // 处理列表
        text = processLists(document, text);
        
        // 处理粗体和斜体
        text = processInlineStyles(document, text);
        
        // 处理剩余文本
        if (!text.trim().isEmpty()) {
            addParagraph(document, text, false, false);
        }
    }

    /**
     * 处理标题
     */
    private String processHeadings(XWPFDocument document, String html) {
        Pattern h1Pattern = Pattern.compile("<h1[^>]*>(.*?)</h1>", Pattern.DOTALL);
        Pattern h2Pattern = Pattern.compile("<h2[^>]*>(.*?)</h2>", Pattern.DOTALL);
        Pattern h3Pattern = Pattern.compile("<h3[^>]*>(.*?)</h3>", Pattern.DOTALL);
        Pattern h4Pattern = Pattern.compile("<h4[^>]*>(.*?)</h4>", Pattern.DOTALL);
        
        html = replaceAndAddHeading(document, html, h1Pattern, 1);
        html = replaceAndAddHeading(document, html, h2Pattern, 2);
        html = replaceAndAddHeading(document, html, h3Pattern, 3);
        html = replaceAndAddHeading(document, html, h4Pattern, 4);
        
        return html;
    }

    /**
     * 替换并添加标题
     */
    private String replaceAndAddHeading(XWPFDocument document, String html, Pattern pattern, int level) {
        Matcher matcher = pattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String text = matcher.group(1).replaceAll("<[^>]+>", ""); // 移除内部HTML标签
            addHeading(document, text, level);
            matcher.appendReplacement(sb, "");
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }

    /**
     * 处理段落
     */
    private String processParagraphs(XWPFDocument document, String html) {
        Pattern pPattern = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL);
        Matcher matcher = pPattern.matcher(html);
        StringBuffer sb = new StringBuffer();
        
        while (matcher.find()) {
            String text = matcher.group(1).replaceAll("<[^>]+>", ""); // 移除HTML标签
            if (!text.trim().isEmpty()) {
                addParagraph(document, text, false, false);
            }
            matcher.appendReplacement(sb, "");
        }
        matcher.appendTail(sb);
        
        return sb.toString();
    }

    /**
     * 处理列表
     */
    private String processLists(XWPFDocument document, String html) {
        // 处理无序列表
        Pattern ulPattern = Pattern.compile("<ul[^>]*>(.*?)</ul>", Pattern.DOTALL);
        Matcher ulMatcher = ulPattern.matcher(html);
        StringBuffer ulSb = new StringBuffer();
        
        while (ulMatcher.find()) {
            String listContent = ulMatcher.group(1);
            processListItem(document, listContent, false);
            ulMatcher.appendReplacement(ulSb, "");
        }
        ulMatcher.appendTail(ulSb);
        html = ulSb.toString();
        
        // 处理有序列表
        Pattern olPattern = Pattern.compile("<ol[^>]*>(.*?)</ol>", Pattern.DOTALL);
        Matcher olMatcher = olPattern.matcher(html);
        StringBuffer olSb = new StringBuffer();
        
        while (olMatcher.find()) {
            String listContent = olMatcher.group(1);
            processListItem(document, listContent, true);
            olMatcher.appendReplacement(olSb, "");
        }
        olMatcher.appendTail(olSb);
        
        return olSb.toString();
    }

    /**
     * 处理列表项
     */
    private void processListItem(XWPFDocument document, String listContent, boolean ordered) {
        Pattern liPattern = Pattern.compile("<li[^>]*>(.*?)</li>", Pattern.DOTALL);
        Matcher matcher = liPattern.matcher(listContent);
        
        while (matcher.find()) {
            String text = matcher.group(1).replaceAll("<[^>]+>", ""); // 移除HTML标签
            if (!text.trim().isEmpty()) {
                addListItem(document, text, ordered);
            }
        }
    }

    /**
     * 处理内联样式
     */
    private String processInlineStyles(XWPFDocument document, String html) {
        // 处理粗体
        Pattern boldPattern = Pattern.compile("<(?:b|strong)[^>]*>(.*?)</(?:b|strong)>", Pattern.DOTALL);
        Matcher boldMatcher = boldPattern.matcher(html);
        StringBuffer boldSb = new StringBuffer();
        
        while (boldMatcher.find()) {
            String text = boldMatcher.group(1).replaceAll("<[^>]+>", ""); // 移除内部HTML标签
            if (!text.trim().isEmpty()) {
                addParagraph(document, text, true, false);
            }
            boldMatcher.appendReplacement(boldSb, "");
        }
        boldMatcher.appendTail(boldSb);
        html = boldSb.toString();
        
        // 处理斜体
        Pattern italicPattern = Pattern.compile("<(?:i|em)[^>]*>(.*?)</(?:i|em)>", Pattern.DOTALL);
        Matcher italicMatcher = italicPattern.matcher(html);
        StringBuffer italicSb = new StringBuffer();
        
        while (italicMatcher.find()) {
            String text = italicMatcher.group(1).replaceAll("<[^>]+>", ""); // 移除内部HTML标签
            if (!text.trim().isEmpty()) {
                addParagraph(document, text, false, true);
            }
            italicMatcher.appendReplacement(italicSb, "");
        }
        italicMatcher.appendTail(italicSb);
        
        return italicSb.toString();
    }

    /**
     * 添加标题
     */
    private void addHeading(XWPFDocument document, String text, int level) {
        XWPFParagraph paragraph = document.createParagraph();
        
        // 设置标题样式
        switch (level) {
            case 1:
                paragraph.setStyle("Heading1");
                break;
            case 2:
                paragraph.setStyle("Heading2");
                break;
            case 3:
                paragraph.setStyle("Heading3");
                break;
            default:
                paragraph.setStyle("Heading4");
        }
        
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        
        // 根据级别设置字体大小
        switch (level) {
            case 1:
                run.setFontSize(24);
                break;
            case 2:
                run.setFontSize(20);
                break;
            case 3:
                run.setFontSize(16);
                break;
            default:
                run.setFontSize(14);
        }
    }

    /**
     * 添加段落
     */
    private void addParagraph(XWPFDocument document, String text, boolean bold, boolean italic) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setItalic(italic);
    }

    /**
     * 添加列表项
     */
    private void addListItem(XWPFDocument document, String text, boolean numbered) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle(numbered ? "ListNumber" : "ListBullet");
        
        XWPFRun run = paragraph.createRun();
        run.setText(text);
    }

    /**
     * 解析Word文档路径
     */
    private Path resolveDocxPath(String path) {
        return BuiltinTools.resolveOutputPath(path);
    }
}