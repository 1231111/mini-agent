package com.miniagent.agent.tool;

import com.miniagent.agent.tool.impl.HtmlToDocxParams;
import com.miniagent.common.io.AtomicFiles;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.stereotype.Component;

import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/** Converts supported HTML blocks and inline emphasis in source order, without fetching external resources. */
@Component
public class HtmlToDocxTool {
    public String handle(HtmlToDocxParams params) {
        try {
            DocumentResourceBudget.requireText(params.getHtmlContent(), "HTML content");
            DocumentResourceBudget.requireText(params.getTitle(), "title");
            DocumentResourceBudget.requireText(params.getAuthor(), "author");
            Path target = BuiltinTools.resolveOutputPath(params.getPath());
            try (XWPFDocument document = new XWPFDocument()) {
                WriteDocxTool.initializeStyles(document);
                if (params.getTitle() != null) document.getProperties().getCoreProperties().setTitle(params.getTitle());
                if (params.getAuthor() != null) document.getProperties().getCoreProperties().setCreator(params.getAuthor());
                HtmlWriter writer = new HtmlWriter(document, params.isPreserveStylesOrDefault());
                new ParserDelegator().parse(new StringReader(params.getHtmlContent() == null ? "" : params.getHtmlContent()),
                        writer, true);
                // Swing's HTML parser can catch callback exceptions; never commit a partially converted document.
                writer.requireCompleted();
                DocumentResourceBudget.write(target, document::write);
            }
            return "{\"success\":true,\"path\":\"" + BuiltinTools.jsonEscape(target.toString().replace('\\', '/'))
                    + "\",\"size\":" + Files.size(target)
                    + ",\"message\":\"HTML正文已按顺序转换；支持标题、段落、列表和粗体斜体，不支持CSS布局与外部资源\"}";
        } catch (Exception failure) {
            return ToolResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "HTML转Word文档失败: " + failure.getMessage(), false).legacyText();
        }
    }

    private static final class HtmlWriter extends HTMLEditorKit.ParserCallback {
        private static final Set<String> BLOCKS = Set.of("p", "div", "section", "article", "header", "footer",
                "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "tr");
        private final XWPFDocument document;
        private final boolean preserveStyles;
        private final Deque<Boolean> orderedLists = new ArrayDeque<>();
        private XWPFParagraph current;
        private int bold;
        private int italic;
        private int ignored;
        private int nodes;
        private int blocks;
        private int runs;
        private int textChars;
        private int depth;
        private IllegalArgumentException budgetFailure;

        HtmlWriter(XWPFDocument document, boolean preserveStyles) {
            this.document = document;
            this.preserveStyles = preserveStyles;
        }

        @Override
        public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            countNode();
            if (++depth > DocumentResourceBudget.MAX_XML_DEPTH)
                throw exceeded("HTML 嵌套深度超过上限 " + DocumentResourceBudget.MAX_XML_DEPTH);
            String name = tag.toString();
            if ("script".equals(name) || "style".equals(name) || "head".equals(name)) { ignored++; return; }
            if (ignored > 0) return;
            if ("ol".equals(name) || "ul".equals(name)) { orderedLists.push("ol".equals(name)); current = null; }
            if (BLOCKS.contains(name)) {
                current = newParagraph();
                if (name.matches("h[1-6]")) current.setStyle("Heading" + Math.min(4, name.charAt(1) - '0'));
                if ("li".equals(name)) {
                    boolean ordered = !orderedLists.isEmpty() && orderedLists.peek();
                    current.setStyle(ordered ? "ListNumber" : "ListBullet");
                    WriteDocxTool.applyListNumbering(document, current, ordered);
                }
            }
            if ("b".equals(name) || "strong".equals(name)) bold++;
            if ("i".equals(name) || "em".equals(name)) italic++;
        }

        @Override
        public void handleEndTag(HTML.Tag tag, int position) {
            depth = Math.max(0, depth - 1);
            String name = tag.toString();
            if ("script".equals(name) || "style".equals(name) || "head".equals(name)) { ignored = Math.max(0, ignored - 1); return; }
            if (ignored > 0) return;
            if ("b".equals(name) || "strong".equals(name)) bold = Math.max(0, bold - 1);
            if ("i".equals(name) || "em".equals(name)) italic = Math.max(0, italic - 1);
            if ("ol".equals(name) || "ul".equals(name)) { if (!orderedLists.isEmpty()) orderedLists.pop(); current = null; }
            if (BLOCKS.contains(name)) current = null;
        }

        @Override
        public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
            countNode();
            if (ignored == 0 && HTML.Tag.BR.equals(tag)) newRun().addBreak();
        }

        @Override
        public void handleText(char[] data, int position) {
            if (ignored > 0) return;
            String value = new String(data);
            if (value.isBlank() && current == null) return;
            textChars += value.length();
            if (textChars > DocumentResourceBudget.MAX_CONTENT_CHARS) throw exceeded("HTML 输出文本超过字符预算");
            XWPFRun run = newRun();
            run.setText(value);
            if (preserveStyles) { run.setBold(bold > 0); run.setItalic(italic > 0); }
        }

        private XWPFParagraph paragraph() {
            if (current == null) current = newParagraph();
            return current;
        }

        private XWPFParagraph newParagraph() {
            if (++blocks > DocumentResourceBudget.MAX_BLOCKS) throw exceeded("HTML 段落数超过上限 " + DocumentResourceBudget.MAX_BLOCKS);
            return document.createParagraph();
        }

        private XWPFRun newRun() {
            if (++runs > DocumentResourceBudget.MAX_RUNS) throw exceeded("HTML run 数量超过上限 " + DocumentResourceBudget.MAX_RUNS);
            return paragraph().createRun();
        }

        private void countNode() {
            requireCompleted();
            if (Thread.currentThread().isInterrupted()) throw exceeded("HTML 转换已取消");
            if (++nodes > DocumentResourceBudget.MAX_XML_ELEMENTS) throw exceeded("HTML 节点数超过安全上限");
        }

        private IllegalArgumentException exceeded(String message) {
            if (budgetFailure == null) budgetFailure = new IllegalArgumentException(message);
            return budgetFailure;
        }

        private void requireCompleted() { if (budgetFailure != null) throw budgetFailure; }
    }
}
