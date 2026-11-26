package com.miniagent.agent.tool;

import com.miniagent.agent.tool.impl.EditDocumentParams;
import com.miniagent.common.io.AtomicFiles;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.Set;

/**
 * 智能文档编辑工具：支持通过自然语言修改已有文档的内容
 */
@Slf4j
@Component
public class EditDocumentTool {

    @Autowired
    private ToolRegistry toolRegistry;

    @PostConstruct
    public void register() {
        toolRegistry.register(
                "edit_document",
                "智能文档编辑工具：通过自然语言修改已有文档的内容。\n" +
                "什么时候用本工具：目标是 Word 二进制文档（.docx）——这类文件 edit_file 读不了；\n" +
                "或者你只想用一句话描述改动（\"把标题改成XX\"），不想手工定位锚点。\n" +
                "什么时候改用 edit_file：目标是 .md/.txt/.html 等纯文本、且你清楚确切改动位置——\n" +
                "edit_file 用锚点做精确替换，更可控，不会误伤其它段落。\n\n" +
                "支持的操作：\n" +
                "- 修改标题：\"把标题改成XX\"、\"标题不是XX格式\"\n" +
                "- 替换内容：\"把'旧内容'改成'新内容'\"\n" +
                "- 追加内容：\"在文档末尾添加XX\"\n" +
                "- 插入内容：operation=prepend 在开头添加；operation=append 在末尾添加\n" +
                "- 删除内容：\"删除XX部分\"\n\n" +
                "支持格式：Word(.docx)、Markdown(.md)、HTML(.html)、纯文本(.txt)。不支持 PowerPoint/Excel 编辑、Word 的锚点插入或复杂域/超链接结构替换。\n\n" +
                "示例：\n" +
                "- \"把报告的标题改成'项目总结'\"\n" +
                "- \"把'第一季度'替换成'第二季度'\"\n" +
                "- \"在文档末尾添加结论部分\"\n" +
                "- \"删除第三段的内容\"",
                EditDocumentParams.class,
                this::handle
        );
    }

    private String handle(EditDocumentParams params) {
        try {
            validateOperation(params);
            for (String value : new String[]{params.getInstruction(), params.getFindText(), params.getReplaceText(),
                    params.getNewTitle(), params.getNewContent()}) DocumentResourceBudget.requireText(value, "文档编辑参数");
            Path targetPath = resolvePath(params.getPath());
            
            if (!targetPath.toFile().exists()) {
                return "{\"success\":false,\"error\":\"文件不存在: " + params.getPath() + "\"}";
            }
            
            String format = detectFormat(params.getPath());
            log.info("智能文档编辑: 格式={}, 路径={}, 指令={}", format, params.getPath(), params.getInstruction());
            
            // 根据格式分发到对应的编辑方法
            return switch (format) {
                case "docx" -> editDocx(targetPath, params);
                case "pptx" -> editPptx(targetPath, params);
                case "xlsx" -> editXlsx(targetPath, params);
                case "md", "markdown", "txt" -> editText(targetPath, params);
                case "html" -> editHtml(targetPath, params);
                default -> "{\"success\":false,\"error\":\"不支持的格式: " + format + "\"}";
            };
            
        } catch (Exception e) {
            log.error("智能文档编辑失败", e);
            return failure("文档编辑失败: " + e.getMessage());
        }
    }

    /**
     * 编辑Word文档
     */
    private String editDocx(Path path, EditDocumentParams params) {
        try {
        byte[] original = DocumentResourceBudget.readStableFile(path);
        DocumentResourceBudget.validateDocx(original);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(original))) {
            validateWordWork(document);
            
            boolean modified = false;
            
            // 修改标题
            if (params.getNewTitle() != null && !params.getNewTitle().isEmpty()) {
                modified = modifyDocxTitle(document, params.getNewTitle()) || modified;
            }
            
            // 精确替换
            if (params.getFindText() != null && !params.getFindText().isEmpty() && 
                params.getReplaceText() != null) {
                modified = replaceInDocx(document, params.getFindText(), params.getReplaceText()) || modified;
            }
            
            // 根据操作类型处理内容
            String operation = params.getOperationOrDefault();
            if (params.getNewContent() != null && !params.getNewContent().isEmpty()) {
                switch (operation) {
                    case "append":
                        appendToDocx(document, params.getNewContent());
                        modified = true;
                        break;
                    case "prepend":
                        prependToDocx(document, params.getNewContent());
                        modified = true;
                        break;
                    case "delete":
                        if (!deleteFromDocx(document, params.getNewContent()))
                            throw new IllegalArgumentException("未找到需要删除的 Word 段落；本次请求的全部修改均未提交");
                        modified = true;
                        break;
                    default:
                        // 智能替换：根据指令判断
                        if ("insert_before".equals(operation) || "insert_after".equals(operation))
                            throw new IllegalArgumentException("Word 当前不支持 insert_before/insert_after；请使用明确的 prepend/append 或 findText/replaceText");
                        modified = smartEditDocx(document, params.getInstruction(), params.getNewContent()) || modified;
                }
            }
            
            if (modified) {
                // 原子替换：先写同目录临时文件、再整体移动覆盖。
                // 旧实现是 new FileOutputStream(path)——它先把目标截断，中途失败就是
                // "原文档已空、新内容没写进去"，用户文档当场丢失且无法回滚。
                validateWordWork(document);
                DocumentResourceBudget.writeUnchanged(path, original, document::write);
                
                return String.format(
                        "{\"success\":true,\"path\":\"%s\",\"message\":\"文档修改成功\"}",
                        BuiltinTools.jsonEscape(path.toString().replace("\\", "/"))
                );
            } else {
                return "{\"success\":false,\"error\":\"未找到需要修改的内容\"}";
            }
        }
            
        } catch (Exception e) {
            return failure("Word文档编辑失败: " + e.getMessage());
        }
    }

    private static void validateOperation(EditDocumentParams params) {
        String requested = params.getOperation();
        String operation = requested == null || requested.isBlank() ? params.getOperationOrDefault()
                : requested.trim().toLowerCase(Locale.ROOT);
        if (!Set.of("replace", "append", "prepend", "delete").contains(operation))
            throw new IllegalArgumentException("不支持的文档操作: " + operation + "；请使用 replace/append/prepend/delete");
        boolean exact = params.getFindText() != null || params.getReplaceText() != null;
        if (exact && (params.getFindText() == null || params.getFindText().isEmpty() || params.getReplaceText() == null))
            throw new IllegalArgumentException("精确替换必须同时提供非空 findText 和 replaceText（replaceText 可为空）");
        if (params.getNewTitle() != null && params.getNewTitle().isEmpty())
            throw new IllegalArgumentException("newTitle 不能为空字符串");
        if (params.getNewContent() != null && params.getNewContent().isEmpty())
            throw new IllegalArgumentException("newContent 不能为空字符串");
        if (!"replace".equals(operation) && params.getNewContent() == null)
            throw new IllegalArgumentException(operation + " 必须提供 newContent，本次请求未写入文件");
        if ("replace".equals(operation) && exact && params.getNewContent() != null)
            throw new IllegalArgumentException("精确替换使用 replaceText；不要同时提供含义不明确的 newContent");
        if (!exact && params.getNewContent() == null && params.getNewTitle() == null)
            throw new IllegalArgumentException("请提供 findText/replaceText、newTitle 或 newContent");
        if (requested != null && !requested.isBlank()) params.setOperation(operation);
    }

    /**
     * 修改Word文档标题
     */
    private boolean modifyDocxTitle(XWPFDocument document, String newTitle) {
        // 方法1：修改文档属性标题
        boolean modified = !Objects.equals(document.getProperties().getCoreProperties().getTitle(), newTitle);
        document.getProperties().getCoreProperties().setTitle(newTitle);
        
        // 方法2：修改第一个段落（如果看起来像标题）
        if (document.getParagraphs().size() > 0) {
            XWPFParagraph firstPara = document.getParagraphs().get(0);
            if (firstPara.getStyle() != null && firstPara.getStyle().startsWith("Heading")) {
                if (!firstPara.getText().equals(newTitle)) {
                    if (firstPara.getText().isEmpty()) firstPara.createRun().setText(newTitle);
                    else replaceParagraphText(firstPara, firstPara.getText(), newTitle);
                    modified = true;
                }
            }
        }
        
        return modified;
    }

    /**
     * 在Word文档中替换文本
     */
    private boolean replaceInDocx(XWPFDocument document, String findText, String replaceText) {
        boolean found = false;
        long matchCount = 0;
        List<XWPFParagraph> paragraphs = wordParagraphs(document);
        long finalTextLength = paragraphs.stream().mapToLong(p -> p.getText().length()).sum();
        for (XWPFParagraph paragraph : paragraphs) {
            String text = paragraph.getText();
            int matches = countMatches(text, findText);
            matchCount += matches;
            finalTextLength += (long) matches * (replaceText.length() - findText.length());
            if (finalTextLength > DocumentResourceBudget.MAX_CONTENT_CHARS)
                throw new IllegalArgumentException("替换结果超过文档文本上限 " + DocumentResourceBudget.MAX_CONTENT_CHARS + " 字符");
        }
        if (matchCount == 0) throw new IllegalArgumentException("未找到精确替换的原文；本次请求的全部修改均未提交");
        if (findText.equals(replaceText)) return false;
        for (XWPFParagraph paragraph : paragraphs)
            found = replaceParagraphText(paragraph, findText, replaceText) || found;
        return found;
    }

    private static int countMatches(String text, String target) {
        int count = 0;
        for (int at = text.indexOf(target); at >= 0; at = text.indexOf(target, at + target.length())) count++;
        return count;
    }

    /** Exact replacements across ordinary w:r/w:t runs, preserving unaffected runs and run formatting. */
    private static boolean replaceParagraphText(XWPFParagraph paragraph, String find, String replacement) {
        String text = paragraph.getText();
        if (find.isEmpty() || !text.contains(find) || find.equals(replacement)) return false;
        var children = paragraph.getCTP().getDomNode().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            String local = children.item(i).getLocalName();
            if (!"r".equals(local) && !"pPr".equals(local))
                throw new IllegalArgumentException("匹配段落包含域、超链接、书签或修订结构，当前仅支持普通文本 run 的精确替换");
        }
        List<XWPFRun> runs = paragraph.getRuns();
        StringBuilder plain = new StringBuilder();
        int[] starts = new int[runs.size()];
        for (int i = 0; i < runs.size(); i++) {
            starts[i] = plain.length();
            var nodes = runs.get(i).getCTR().getDomNode().getChildNodes();
            for (int j = 0; j < nodes.getLength(); j++) {
                String local = nodes.item(j).getLocalName();
                if (!"t".equals(local) && !"rPr".equals(local))
                    throw new IllegalArgumentException("匹配段落含制表符、换行、图形或域，当前不支持安全替换");
            }
            plain.append(runs.get(i).text());
        }
        if (!plain.toString().equals(text)) throw new IllegalArgumentException("段落包含非普通文本结构，拒绝有损替换");
        List<Integer> matches = new ArrayList<>();
        for (int at = text.indexOf(find); at >= 0; at = text.indexOf(find, at + find.length())) matches.add(at);
        for (int m = matches.size() - 1; m >= 0; m--) {
            int from = matches.get(m), to = from + find.length();
            int startRun = 0, endRun = runs.size() - 1;
            while (startRun + 1 < runs.size() && starts[startRun + 1] <= from) startRun++;
            while (endRun > startRun && starts[endRun] >= to) endRun--;
            String first = runs.get(startRun).text();
            String last = runs.get(endRun).text();
            String prefix = first.substring(0, from - starts[startRun]);
            String suffix = last.substring(to - starts[endRun]);
            setRunText(runs.get(startRun), prefix + replacement + (startRun == endRun ? suffix : ""));
            for (int i = startRun + 1; i < endRun; i++) setRunText(runs.get(i), "");
            if (startRun != endRun) setRunText(runs.get(endRun), suffix);
        }
        return true;
    }

    private static void setRunText(XWPFRun run, String text) {
        while (run.getCTR().sizeOfTArray() > 0) run.getCTR().removeT(0);
        run.setText(text);
    }

    private static List<XWPFParagraph> wordParagraphs(XWPFDocument document) {
        List<XWPFParagraph> paragraphs = new ArrayList<>();
        collectParagraphs(document.getBodyElements(), paragraphs, 0);
        for (var header : document.getHeaderList()) collectParagraphs(header.getBodyElements(), paragraphs, 0);
        for (var footer : document.getFooterList()) collectParagraphs(footer.getBodyElements(), paragraphs, 0);
        return paragraphs;
    }

    private static void collectParagraphs(List<IBodyElement> elements, List<XWPFParagraph> out, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Word 表格嵌套超过上限 32");
        for (IBodyElement element : elements) {
            if (element instanceof XWPFParagraph paragraph) out.add(paragraph);
            else if (element instanceof XWPFTable table)
                for (XWPFTableRow row : table.getRows())
                    for (XWPFTableCell cell : row.getTableCells()) collectParagraphs(cell.getBodyElements(), out, depth + 1);
            if (out.size() > DocumentResourceBudget.MAX_BLOCKS) throw new IllegalArgumentException("Word 段落数超过上限");
        }
    }

    private static void validateWordWork(XWPFDocument document) {
        long runs = 0, chars = 0;
        for (XWPFParagraph paragraph : wordParagraphs(document)) {
            runs += paragraph.getRuns().size();
            chars += paragraph.getText().length();
            if (runs > DocumentResourceBudget.MAX_RUNS || chars > DocumentResourceBudget.MAX_CONTENT_CHARS)
                throw new IllegalArgumentException("Word 文本或 run 工作量超过安全上限");
        }
    }

    /**
     * 在Word文档末尾追加内容
     */
    private void appendToDocx(XWPFDocument document, String content) {
        XWPFParagraph paragraph = document.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setText(content);
    }

    /**
     * 在Word文档开头插入内容
     */
    private void prependToDocx(XWPFDocument document, String content) {
        XWPFParagraph paragraph;
        if (document.getBodyElements().isEmpty()) {
            paragraph = document.createParagraph();
        } else {
            try (var cursor = document.getDocument().getBody().newCursor()) {
                cursor.toFirstChild();
                paragraph = document.insertNewParagraph(cursor);
            }
        }
        XWPFRun run = paragraph.createRun();
        run.setText(content);
    }

    /**
     * 从Word文档中删除内容
     */
    private boolean deleteFromDocx(XWPFDocument document, String content) {
        boolean modified = false;
        // Work on a snapshot because removing a paragraph mutates its parent's element list.
        List<XWPFParagraph> paragraphs = wordParagraphs(document);
        for (int i = paragraphs.size() - 1; i >= 0; i--) {
            XWPFParagraph paragraph = paragraphs.get(i);
            if (paragraph.getText().contains(content)) {
                if (paragraph.getBody() instanceof XWPFTableCell cell) {
                    cell.removeParagraph(cell.getParagraphs().indexOf(paragraph));
                    // A Word table cell must end in a paragraph, including otherwise empty cells.
                    if (cell.getBodyElements().isEmpty() || !(cell.getBodyElements().get(cell.getBodyElements().size() - 1) instanceof XWPFParagraph))
                        cell.addParagraph();
                } else if (paragraph.getBody() instanceof XWPFHeaderFooter headerFooter) {
                    headerFooter.removeParagraph(paragraph);
                } else if (paragraph.getBody() instanceof XWPFDocument bodyDocument) {
                    bodyDocument.removeBodyElement(bodyDocument.getPosOfParagraph(paragraph));
                } else {
                    throw new IllegalArgumentException("不支持安全删除此 Word 段落所在的结构");
                }
                modified = true;
            }
        }
        return modified;
    }

    /**
     * 智能编辑Word文档
     */
    private boolean smartEditDocx(XWPFDocument document, String instruction, String newContent) {
        // 根据指令智能判断如何编辑
        String lowerInstruction = instruction == null ? "" : instruction.toLowerCase();
        
        if (lowerInstruction.contains("标题") || lowerInstruction.contains("title")) {
            return modifyDocxTitle(document, newContent);
        } else if (lowerInstruction.contains("追加") || lowerInstruction.contains("末尾")) {
            appendToDocx(document, newContent);
            return true;
        } else if (lowerInstruction.contains("替换") || lowerInstruction.contains("改成")) {
            // 尝试提取查找文本
            Pattern pattern = Pattern.compile("['\"](.+?)['\"]");
            Matcher matcher = pattern.matcher(instruction);
            if (matcher.find()) {
                String findText = matcher.group(1);
                return replaceInDocx(document, findText, newContent);
            }
            throw new IllegalArgumentException("智能替换未提供可验证的查找文本，请使用 findText/replaceText");
        } else {
            throw new IllegalArgumentException("无法可靠识别文档操作，请显式指定 operation=append/prepend/delete，或提供 findText/replaceText");
        }
    }

    /**
     * 编辑PowerPoint
     */
    private String editPptx(Path path, EditDocumentParams params) {
        // PowerPoint编辑逻辑类似Word
        // 这里简化处理，实际应用中需要完整的实现
        return "{\"success\":false,\"error\":\"PowerPoint编辑功能正在开发中\"}";
    }

    /**
     * 编辑Excel
     */
    private String editXlsx(Path path, EditDocumentParams params) {
        // Excel编辑逻辑
        // 这里简化处理，实际应用中需要完整的实现
        return "{\"success\":false,\"error\":\"Excel编辑功能正在开发中\"}";
    }

    /**
     * 编辑文本文件
     */
    private String editText(Path path, EditDocumentParams params) {
        try {
            byte[] original = DocumentResourceBudget.readStableFile(path);
            String content = DocumentResourceBudget.decodeText(original);
            DocumentResourceBudget.requireText(content, "待编辑文本");
            String before = content;

            if (params.getNewTitle() != null) {
                int lineEnd = content.indexOf('\n');
                content = params.getNewTitle() + (lineEnd < 0 ? "" : content.substring(lineEnd));
            }
            
            if (params.getFindText() != null && !params.getFindText().isEmpty() && 
                params.getReplaceText() != null) {
                // 精确替换
                content = boundedReplace(content, params.getFindText(), params.getReplaceText());
            }
            if (params.getNewContent() != null && !params.getNewContent().isEmpty()) {
                // 根据操作类型处理
                String operation = params.getOperationOrDefault();
                switch (operation) {
                    case "append":
                        content = content + "\n" + params.getNewContent();
                        break;
                    case "prepend":
                        content = params.getNewContent() + "\n" + content;
                        break;
                    case "delete":
                        content = boundedReplace(content, params.getNewContent(), "");
                        break;
                    default:
                        // 智能替换
                        content = smartEditText(content, params.getInstruction(), params.getNewContent());
                }
            }
            if (content.equals(before)) return failure("未找到需要修改的内容或新旧内容相同，未写入文件");
            DocumentResourceBudget.requireText(content, "文本编辑结果");
            
            // 同样走原子替换：Files.writeString 默认先截断，写一半失败会留下半个文件。
            final byte[] payload = content.getBytes(StandardCharsets.UTF_8);
            DocumentResourceBudget.writeUnchanged(path, original, out -> out.write(payload));
            
            return String.format(
                    "{\"success\":true,\"path\":\"%s\",\"message\":\"文本文件修改成功\"}",
                    BuiltinTools.jsonEscape(path.toString().replace("\\", "/"))
            );
            
        } catch (Exception e) {
            return failure("文本文件编辑失败: " + e.getMessage());
        }
    }

    /**
     * 智能编辑文本
     */
    private String smartEditText(String content, String instruction, String newContent) {
        String lowerInstruction = instruction == null ? "" : instruction.toLowerCase();
        
        if (lowerInstruction.contains("标题") || lowerInstruction.contains("第一行")) {
            // 修改第一行（标题）
            String[] lines = content.split("\n");
            if (lines.length > 0) {
                lines[0] = newContent;
                return String.join("\n", lines);
            }
        } else if (lowerInstruction.contains("追加") || lowerInstruction.contains("末尾")) {
            return content + "\n" + newContent;
        } else if (lowerInstruction.contains("替换") || lowerInstruction.contains("改成")) {
            // 尝试提取查找文本
            Pattern pattern = Pattern.compile("['\"](.+?)['\"]");
            Matcher matcher = pattern.matcher(instruction);
            if (matcher.find()) {
                String findText = matcher.group(1);
                return boundedReplace(content, findText, newContent);
            }
        }
        
        throw new IllegalArgumentException("无法可靠识别文本编辑操作，请显式指定 operation=append/prepend/delete，或提供 findText/replaceText");
    }

    private static String boundedReplace(String content, String find, String replacement) {
        if (!content.contains(find)) throw new IllegalArgumentException("未找到需要替换或删除的原文；本次请求的全部修改均未提交");
        long finalSize = content.length() + (long) countMatches(content, find) * (replacement.length() - find.length());
        if (finalSize > DocumentResourceBudget.MAX_CONTENT_CHARS)
            throw new IllegalArgumentException("替换结果超过文本字符上限 " + DocumentResourceBudget.MAX_CONTENT_CHARS);
        return content.replace(find, replacement);
    }

    private static String failure(String message) {
        return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENT, message, false).legacyText();
    }

    /**
     * 编辑HTML文件
     */
    private String editHtml(Path path, EditDocumentParams params) {
        // HTML编辑逻辑
        // 这里简化处理，实际应用中需要完整的实现
        return editText(path, params);
    }

    /**
     * 检测文件格式
     */
    private String detectFormat(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".docx")) return "docx";
        else if (lower.endsWith(".pptx")) return "pptx";
        else if (lower.endsWith(".xlsx")) return "xlsx";
        else if (lower.endsWith(".html") || lower.endsWith(".htm")) return "html";
        else if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "md";
        else if (lower.endsWith(".txt")) return "txt";
        else return "txt";
    }

    /**
     * 解析文件路径
     */
    private Path resolvePath(String path) {
        return BuiltinTools.resolveOutputPath(path);
    }
}
