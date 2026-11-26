package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.impl.DocumentStyle;
import com.miniagent.agent.tool.impl.WriteDocxParams;
import com.miniagent.common.io.AtomicFiles;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Word文档写入工具：支持创建和编辑Word文档
 */
@Slf4j
@Component
public class WriteDocxTool {

    @Autowired
    private ToolRegistry toolRegistry;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @PostConstruct
    public void register() {
        toolRegistry.register(
                "write_docx",
                "写入 Word 文档（.docx），content 用 Markdown 写、自动转成 Word 版式。\n" +
                "仅在需要【页眉页脚】时才用本工具（addHeaderFooter=true）。\n" +
                "其余情况用 write_document（path 给 .docx）——它覆盖本工具的全部能力，还多支持\n" +
                "style / fontFamily / fontSize / titleFontSize / textColor / titleColor / alignment / lineSpacing 等样式参数。\n" +
                "两者唯一的实际差别就是页眉页脚：write_document 固定不加。",
                WriteDocxParams.class,
                this::handle
        );
    }

    public String handle(WriteDocxParams params) {
        try {
            DocumentResourceBudget.requireText(params.getContent(), "Word content");
            DocumentResourceBudget.requireText(params.getTitle(), "title");
            DocumentResourceBudget.requireText(params.getAuthor(), "author");
            // 解析路径
            Path targetPath = resolveDocxPath(params.getPath());
            
            // 创建Word文档
            try (XWPFDocument document = new XWPFDocument()) {
            initializeStyles(document);
            
            // 设置文档属性
            if (params.getTitle() != null && !params.getTitle().isEmpty()) {
                document.getProperties().getCoreProperties().setTitle(params.getTitle());
            }
            if (params.getAuthor() != null && !params.getAuthor().isEmpty()) {
                document.getProperties().getCoreProperties().setCreator(params.getAuthor());
            }
            
            // 获取文档样式配置
            DocumentStyle style = params.getDocumentStyle();
            
            // 添加页眉页脚（可选）
            if (params.isAddHeaderFooterOrDefault()) {
                addHeaderFooter(document, params.getTitle());
            }
            
            // 解析并添加内容（传递样式配置）
            String content = params.getContent();
            if (content != null && !content.isEmpty()) {
                addContent(document, content, style);
            }
            
            // 保存文档
            DocumentResourceBudget.write(targetPath, document::write);
            
            long fileSize = targetPath.toFile().length();
            log.info("Word文档已创建: {} ({} 字节)", targetPath.toAbsolutePath(), fileSize);
            
            return String.format(
                    "{\"success\":true,\"path\":\"%s\",\"size\":%d,\"message\":\"Word文档创建成功\"}",
                    targetPath.toString().replace("\\", "/"),
                    fileSize
            );
            }
            
        } catch (Exception e) {
            log.error("创建Word文档失败", e);
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED, "创建Word文档失败: " + e.getMessage(), false).legacyText();
        }
    }

    /**
     * 添加文档内容（支持简单的Markdown格式，使用样式配置）
     */
    private void addContent(XWPFDocument document, String content, DocumentStyle style) {
        if (content.chars().filter(ch -> ch == '\n').count() + 1 > DocumentResourceBudget.MAX_BLOCKS)
            throw new IllegalArgumentException("Word 段落数超过上限 " + DocumentResourceBudget.MAX_BLOCKS);
        String[] lines = content.split("\n");
        
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            
            // 处理标题
            if (line.startsWith("# ")) {
                addHeading(document, line.substring(2), 1, style);
            } else if (line.startsWith("## ")) {
                addHeading(document, line.substring(3), 2, style);
            } else if (line.startsWith("### ")) {
                addHeading(document, line.substring(4), 3, style);
            } else if (line.startsWith("#### ")) {
                addHeading(document, line.substring(5), 4, style);
            }
            // 处理列表
            else if (line.startsWith("- ") || line.startsWith("* ")) {
                addListItem(document, line.substring(2), false, style);
            } else if (line.matches("\\d+\\. .+")) {
                addListItem(document, line.substring(line.indexOf(".") + 2), true, style);
            }
            // 处理引用
            else if (line.startsWith("> ")) {
                addQuote(document, line.substring(2), style);
            }
            // 处理粗体
            else if (line.startsWith("**") && line.endsWith("**")) {
                addParagraph(document, line.substring(2, line.length() - 2), true, false, style);
            }
            // 处理斜体
            else if (line.startsWith("*") && line.endsWith("*")) {
                addParagraph(document, line.substring(1, line.length() - 1), false, true, style);
            }
            // 普通段落
            else {
                addParagraph(document, line, false, false, style);
            }
        }
    }

    /**
     * 添加标题（使用样式配置）
     */
    private void addHeading(XWPFDocument document, String text, int level, DocumentStyle style) {
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
        
        // 设置对齐方式
        if (style.getTitleAlignment() != null) {
            switch (style.getTitleAlignment().toLowerCase()) {
                case "center":
                    paragraph.setAlignment(ParagraphAlignment.CENTER);
                    break;
                case "right":
                    paragraph.setAlignment(ParagraphAlignment.RIGHT);
                    break;
                case "justify":
                    paragraph.setAlignment(ParagraphAlignment.BOTH);
                    break;
                default:
                    paragraph.setAlignment(ParagraphAlignment.LEFT);
            }
        }
        
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(true);
        
        // 使用样式配置的字体
        if (style.getFontFamily() != null) {
            run.setFontFamily(style.getFontFamily());
        }
        
        // 根据级别设置字体大小
        double baseFontSize = style.getTitleFontSize() != null ? style.getTitleFontSize() : 24.0;
        switch (level) {
            case 1:
                run.setFontSize(baseFontSize);
                break;
            case 2:
                run.setFontSize(baseFontSize * 0.83); // 约20
                break;
            case 3:
                run.setFontSize(baseFontSize * 0.67); // 约16
                break;
            default:
                run.setFontSize(baseFontSize * 0.58); // 约14
        }
        
        // 设置标题颜色
        if (style.getTitleColor() != null && !style.getTitleColor().isEmpty()) {
            String color = style.getTitleColor().replace("#", "");
            run.setColor(color);
        }
    }

    /**
     * 添加段落（使用样式配置）
     */
    private void addParagraph(XWPFDocument document, String text, boolean bold, boolean italic, DocumentStyle style) {
        XWPFParagraph paragraph = document.createParagraph();
        
        // 设置对齐方式
        if (style.getAlignment() != null) {
            switch (style.getAlignment().toLowerCase()) {
                case "center":
                    paragraph.setAlignment(ParagraphAlignment.CENTER);
                    break;
                case "right":
                    paragraph.setAlignment(ParagraphAlignment.RIGHT);
                    break;
                case "justify":
                    paragraph.setAlignment(ParagraphAlignment.BOTH);
                    break;
                default:
                    paragraph.setAlignment(ParagraphAlignment.LEFT);
            }
        }
        
        // 设置段落格式
        if (style.getLineSpacing() != null) {
            // POI reuses the paragraph properties rather than introducing a second w:pPr.
            paragraph.setSpacingBetween(style.getLineSpacing());
        }
        if (style.getSpaceBefore() != null) {
            paragraph.setSpacingBefore((int) (style.getSpaceBefore() * 20)); // 20 twips per point
        }
        if (style.getSpaceAfter() != null) {
            paragraph.setSpacingAfter((int) (style.getSpaceAfter() * 20));
        }
        if (style.getFirstLineIndent() != null && style.getFirstLineIndent() > 0) {
            paragraph.setIndentationFirstLine((int) (style.getFirstLineIndent() * 240)); // 240 twips per character
        }
        
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setBold(bold);
        run.setItalic(italic);
        
        // 使用样式配置的字体
        if (style.getFontFamily() != null) {
            run.setFontFamily(style.getFontFamily());
        }
        if (style.getFontSize() != null) {
            run.setFontSize(style.getFontSize());
        }
        
        // 设置文本颜色
        if (style.getTextColor() != null && !style.getTextColor().isEmpty()) {
            String color = style.getTextColor().replace("#", "");
            run.setColor(color);
        }
    }

    /**
     * 添加列表项（使用样式配置）
     */
    private void addListItem(XWPFDocument document, String text, boolean numbered, DocumentStyle style) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle(numbered ? "ListNumber" : "ListBullet");
        applyListNumbering(document, paragraph, numbered);
        
        // 设置列表缩进
        if (style.getListIndent() != null && style.getListIndent() > 0) {
            paragraph.setIndentationLeft((int) (style.getListIndent() * 240));
        }
        
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        
        // 使用样式配置的字体
        if (style.getFontFamily() != null) {
            run.setFontFamily(style.getFontFamily());
        }
        if (style.getFontSize() != null) {
            run.setFontSize(style.getFontSize());
        }
    }

    /**
     * 添加引用（使用样式配置）
     */
    private void addQuote(XWPFDocument document, String text, DocumentStyle style) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setIndentationLeft(720); // 1英寸缩进
        
        XWPFRun run = paragraph.createRun();
        run.setText(text);
        run.setItalic(true);
        run.setColor("666666");
        
        // 使用样式配置的字体
        if (style.getFontFamily() != null) {
            run.setFontFamily(style.getFontFamily());
        }
        if (style.getFontSize() != null) {
            run.setFontSize(style.getFontSize());
        }
    }

    /**
     * 添加页眉页脚
     */
    private void addHeaderFooter(XWPFDocument document, String title) {
        // 添加页眉
        XWPFHeader header = document.createHeader(HeaderFooterType.DEFAULT);
        XWPFParagraph headerParagraph = header.createParagraph();
        XWPFRun headerRun = headerParagraph.createRun();
        headerRun.setText(title != null ? title : "文档标题");
        headerRun.setBold(true);
        headerRun.setFontSize(10);
        
        // 添加页脚
        XWPFFooter footer = document.createFooter(HeaderFooterType.DEFAULT);
        XWPFParagraph footerParagraph = footer.createParagraph();
        footerParagraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun footerRun = footerParagraph.createRun();
        footerRun.setText("第 ");
        footerParagraph.getCTP().addNewFldSimple().setInstr(" PAGE ");
        footerRun = footerParagraph.createRun();
        footerRun.setText(" 页");
    }

    /** Install actual OOXML style and numbering definitions before referencing their identifiers. */
    static void initializeStyles(XWPFDocument document) {
        XWPFStyles styles = document.createStyles();
        for (int level = 0; level <= 4; level++) {
            String id = level == 0 ? "Normal" : "Heading" + level;
            if (styles.styleExist(id)) continue;
            CTStyle definition = CTStyle.Factory.newInstance();
            definition.setStyleId(id);
            definition.setType(STStyleType.PARAGRAPH);
            definition.addNewName().setVal(level == 0 ? "Normal" : "heading " + level);
            if (level > 0) {
                definition.addNewBasedOn().setVal("Normal");
                definition.addNewPPr().addNewOutlineLvl().setVal(BigInteger.valueOf(level - 1));
                var runProperties = definition.addNewRPr();
                runProperties.addNewB();
                runProperties.addNewSz().setVal(BigInteger.valueOf(level == 1 ? 48 : level == 2 ? 40 : level == 3 ? 32 : 28));
            }
            styles.addStyle(new XWPFStyle(definition));
        }
        for (String id : List.of("ListBullet", "ListNumber")) {
            if (styles.styleExist(id)) continue;
            CTStyle definition = CTStyle.Factory.newInstance();
            definition.setStyleId(id);
            definition.setType(STStyleType.PARAGRAPH);
            definition.addNewName().setVal(id);
            definition.addNewBasedOn().setVal("Normal");
            styles.addStyle(new XWPFStyle(definition));
        }
    }

    static void applyListNumbering(XWPFDocument document, XWPFParagraph paragraph, boolean numbered) {
        XWPFNumbering numbering = document.getNumbering();
        if (numbering == null) numbering = document.createNumbering();
        BigInteger abstractId = BigInteger.valueOf(numbered ? 1 : 0);
        if (numbering.getAbstractNum(abstractId) == null) {
            CTAbstractNum definition = CTAbstractNum.Factory.newInstance();
            definition.setAbstractNumId(abstractId);
            CTLvl level = definition.addNewLvl();
            level.setIlvl(BigInteger.ZERO);
            level.addNewStart().setVal(BigInteger.ONE);
            level.addNewNumFmt().setVal(numbered ? STNumberFormat.DECIMAL : STNumberFormat.BULLET);
            level.addNewLvlText().setVal(numbered ? "%1." : "•");
            numbering.addAbstractNum(new XWPFAbstractNum(definition));
        }
        BigInteger numId = abstractId.add(BigInteger.ONE);
        if (!numbering.numExist(numId)) numbering.addNum(abstractId, numId);
        paragraph.setNumID(numId);
        paragraph.setNumILvl(BigInteger.ZERO);
    }

    /**
     * 解析Word文档路径
     */
    private Path resolveDocxPath(String path) {
        return BuiltinTools.resolveOutputPath(path);
    }
}
