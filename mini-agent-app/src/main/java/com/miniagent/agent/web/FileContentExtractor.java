package com.miniagent.agent.web;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * 文件内容提取服务
 * 支持从 base64 编码的文件中提取纯文本内容
 */
@Component
public class FileContentExtractor {

    private static final Logger log = LoggerFactory.getLogger(FileContentExtractor.class);

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    private static final Set<String> TEXT_MIME_TYPES = Set.of(
            "text/plain",
            "text/html",
            "text/css",
            "text/csv",
            "text/markdown",
            "text/xml",
            "application/json",
            "application/xml",
            "application/javascript",
            "application/typescript",
            "application/x-yaml",
            "application/yaml",
            "application/sql",
            "application/x-sh",
            "application/x-shellscript"
    );

    /**
     * 从 base64 编码的文件内容中提取文本
     *
     * @param filename     文件名
     * @param mimeType     MIME 类型
     * @param base64Content base64 编码的文件内容
     * @return 提取的文本内容
     */
    public String extract(String filename, String mimeType, String base64Content) {
        if (base64Content == null || base64Content.isEmpty()) {
            return "[文件内容为空]";
        }

        byte[] data;
        try {
            data = Base64.getDecoder().decode(base64Content);
        } catch (IllegalArgumentException e) {
            log.warn("Base64 解码失败, filename={}", filename, e);
            return "[Base64 解码失败: " + e.getMessage() + "]";
        }

        if (data.length > MAX_FILE_SIZE) {
            return "[文件过大，拒绝处理: " + (data.length / 1024) + "KB，最大允许 2MB]";
        }

        log.info("提取文件内容: filename={}, mimeType={}, size={}bytes", filename, mimeType, data.length);

        try {
            if ("application/pdf".equals(mimeType)) {
                return extractPdf(data);
            } else if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mimeType)) {
                return extractDocx(data);
            } else if ("application/vnd.openxmlformats-officedocument.presentationml.presentation".equals(mimeType)) {
                return extractPptx(data);
            } else if ("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(mimeType)) {
                return extractXlsx(data);
            } else if (isTextType(mimeType) || isTextByExtension(filename)) {
                return decodeText(data, filename);
            } else {
                return "[不支持的文件类型: " + mimeType + "]";
            }
        } catch (Exception e) {
            log.error("文件内容提取失败, filename={}, mimeType={}", filename, mimeType, e);
            return "[文件内容提取失败: " + e.getMessage() + "]";
        }
    }

    /**
     * Decode bytes to text, auto-detecting encoding (UTF-8 -> GBK -> ISO-8859-1)
     */
    private String decodeText(byte[] data, String filename) {
        // Try UTF-8 first (most common)
        String utf8 = new String(data, StandardCharsets.UTF_8);
        // Check for replacement character U+FFFD which indicates invalid UTF-8
        if (!utf8.contains("\ufffd")) {
            return utf8;
        }
        // Try GBK (common for Chinese files)
        try {
            String gbk = new String(data, java.nio.charset.Charset.forName("GBK"));
            if (!gbk.contains("\ufffd")) {
                return gbk;
            }
        } catch (Exception ignored) {}
        // Fallback to UTF-8 (with possible garbled chars)
        return utf8;
    }

    /**
     * 判断是否为文本类型（MIME + 扩展名兜底）
     */
    private boolean isTextType(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        if (TEXT_MIME_TYPES.contains(mimeType)) {
            return true;
        }
        return mimeType.startsWith("text/");
    }

    /**
     * 判断是否为文本类型（按文件扩展名）
     */
    private boolean isTextByExtension(String filename) {
        if (filename == null) return false;
        String lower = filename.toLowerCase();
        String[] textExts = {".txt", ".csv", ".json", ".md", ".xml", ".html", ".htm",
                ".css", ".js", ".ts", ".py", ".java", ".sql", ".sh", ".bat",
                ".yml", ".yaml", ".toml", ".ini", ".cfg", ".conf", ".log",
                ".c", ".cpp", ".h", ".hpp", ".go", ".rs", ".rb", ".php"};
        for (String ext : textExts) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * 提取 PDF 文本内容
     */
    private String extractPdf(byte[] data) throws IOException {
        try (PDDocument doc = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            return text.isEmpty() ? "[PDF 文件无可提取的文本内容]" : text;
        }
    }

    /**
     * 提取 DOCX 文本内容
     */
    private String extractDocx(byte[] data) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
            StringBuilder sb = new StringBuilder();

            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (text != null && !text.trim().isEmpty()) {
                    sb.append(text).append("\n");
                }
            }

            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String text = cell.getText();
                        if (text != null && !text.trim().isEmpty()) {
                            sb.append(text).append("\t");
                        }
                    }
                    sb.append("\n");
                }
            }

            String result = sb.toString().trim();
            return result.isEmpty() ? "[DOCX 文件无可提取的文本内容]" : result;
        }
    }

    /**
     * 提取 PPTX 文本内容
     */
    private String extractPptx(byte[] data) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(data))) {
            StringBuilder sb = new StringBuilder();

            for (XSLFSlide slide : ppt.getSlides()) {
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape) {
                        String text = ((XSLFTextShape) shape).getText();
                        if (text != null && !text.trim().isEmpty()) {
                            sb.append(text).append("\n");
                        }
                    }
                }
                sb.append("\n");
            }

            String result = sb.toString().trim();
            return result.isEmpty() ? "[PPTX 文件无可提取的文本内容]" : result;
        }
    }

    /**
     * 提取 XLSX 文本内容
     */
    private String extractXlsx(byte[] data) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                XSSFSheet sheet = workbook.getSheetAt(i);
                sb.append("--- ").append(sheet.getSheetName()).append(" ---\n");

                for (int rowIdx = sheet.getFirstRowNum(); rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                    XSSFRow row = sheet.getRow(rowIdx);
                    if (row == null) continue;

                    StringBuilder rowStr = new StringBuilder();
                    for (int cellIdx = row.getFirstCellNum(); cellIdx < row.getLastCellNum(); cellIdx++) {
                        if (cellIdx > row.getFirstCellNum()) {
                            rowStr.append("\t");
                        }
                        XSSFCell cell = row.getCell(cellIdx);
                        if (cell != null) {
                            rowStr.append(cell.toString());
                        }
                    }
                    if (rowStr.length() > 0) {
                        sb.append(rowStr).append("\n");
                    }
                }
                sb.append("\n");
            }

            String result = sb.toString().trim();
            return result.isEmpty() ? "[XLSX 文件无可提取的文本内容]" : result;
        }
    }
}
