package com.miniagent.agent.web;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.Objects;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;

/**
 * 统一文件文本提取：MIME + 扩展名双通道识别，支持磁盘路径与 base64。
 * 旧式 OLE（.doc/.ppt/.xls）明确拒绝；超大内容按预算截断并标注。
 */
@Component
public class FileContentExtractor {

    private static final Logger log = LoggerFactory.getLogger(FileContentExtractor.class);

    /** 原始二进制上限 */
    @Value("${file.extract.max-bytes:10485760}")
    private long maxFileBytes;

    /** 单文件注入上下文的字符上限（截断后可继续用侧车全文） */
    @Value("${file.extract.max-inline-chars:80000}")
    private int maxInlineChars;

    private static final Set<String> TEXT_MIME_TYPES = Set.of(
            "text/plain", "text/html", "text/css", "text/csv", "text/markdown", "text/xml",
            "application/json", "application/xml", "application/javascript", "application/typescript",
            "application/x-yaml", "application/yaml", "application/sql",
            "application/x-sh", "application/x-shellscript"
    );

    public enum DocKind {
        PDF, DOCX, PPTX, XLSX, TEXT, LEGACY_OLE, UNSUPPORTED
    }

    /** 兼容旧调用：返回纯文本或错误占位串 */
    public String extract(String filename, String mimeType, String base64Content) {
        ExtractResult r = extractDetailed(filename, mimeType, base64Content);
        if (!r.success()) {
            return "[" + r.error() + "]";
        }
        return r.text();
    }

    public ExtractResult extractDetailed(String filename, String mimeType, String base64Content) {
        if (StringUtils.isEmpty(base64Content)) {
            return ExtractResult.fail(null, "文件内容为空");
        }
        byte[] data;
        try {
            data = Base64.getDecoder().decode(base64Content);
        } catch (IllegalArgumentException e) {
            return ExtractResult.fail(null, "Base64 解码失败: " + e.getMessage());
        }
        return extractFromBytes(filename, mimeType, data);
    }

    public ExtractResult extractFromPath(Path path, String filename, String mimeType) {
        if (Objects.isNull(path) || !Files.isRegularFile(path)) {
            return ExtractResult.fail(null, "文件不存在: " + path);
        }
        try {
            long size = Files.size(path);
            if (size > maxFileBytes) {
                return ExtractResult.fail(resolveKind(filename, mimeType).name().toLowerCase(Locale.ROOT),
                        "文件过大: " + (size / 1024) + "KB，最大允许 " + (maxFileBytes / 1024) + "KB");
            }
            byte[] data = Files.readAllBytes(path);
            String name = Optional.ofNullable(filename).orElse(path.getFileName().toString());
            return extractFromBytes(name, mimeType, data);
        } catch (IOException e) {
            log.warn("读取文件失败: {}", path, e);
            return ExtractResult.fail(null, "读取文件失败: " + e.getMessage());
        }
    }

    public ExtractResult extractFromBytes(String filename, String mimeType, byte[] data) {
        return extractFromBytes(filename, mimeType, data, true);
    }

    /** 不分段截断的完整文本（写侧车 / 二次加工用） */
    public ExtractResult extractFullRaw(String filename, String mimeType, byte[] data) {
        return extractFromBytes(filename, mimeType, data, false);
    }

    private ExtractResult extractFromBytes(String filename, String mimeType, byte[] data, boolean applyInlineLimit) {
        if (Objects.isNull(data) || data.length == 0) {
            return ExtractResult.fail(null, "文件内容为空");
        }
        if (data.length > maxFileBytes) {
            return ExtractResult.fail(null,
                    "文件过大: " + (data.length / 1024) + "KB，最大允许 " + (maxFileBytes / 1024) + "KB");
        }

        DocKind kind = resolveKind(filename, mimeType);
        String format = kind.name().toLowerCase(Locale.ROOT);
        log.info("提取文件内容: filename={}, mimeType={}, kind={}, size={}bytes, inlineLimit={}",
                filename, mimeType, kind, data.length, applyInlineLimit);

        try {
            String raw = switch (kind) {
                case PDF -> extractPdf(data);
                case DOCX -> extractDocx(data);
                case PPTX -> extractPptx(data);
                case XLSX -> extractXlsx(data);
                case TEXT -> decodeText(data);
                case LEGACY_OLE -> null;
                case UNSUPPORTED -> null;
            };
            if (kind == DocKind.LEGACY_OLE) {
                return ExtractResult.fail(format,
                        "不支持旧版 Office 二进制格式（.doc/.ppt/.xls）。请另存为 .docx / .pptx / .xlsx 后重试");
            }
            if (kind == DocKind.UNSUPPORTED) {
                return ExtractResult.fail(format,
                        "不支持的文件类型: mime=" + mimeType + ", file=" + filename
                                + "。支持: pdf/docx/pptx/xlsx/md/txt/csv/json 等文本");
            }
            if (StringUtils.isBlank(raw)) {
                return ExtractResult.fail(format, "无可提取的文本内容（可能是扫描件或空文档）");
            }
            int original = raw.length();
            boolean truncated = applyInlineLimit && original > maxInlineChars;
            String text = truncated
                    ? raw.substring(0, maxInlineChars) + "\n\n…[已截断，原文 " + original
                    + " 字符；完整文本见侧车 .extracted.txt 或使用 read_file]"
                    : raw;
            return ExtractResult.ok(format, text, truncated, original);
        } catch (Exception e) {
            log.error("文件内容提取失败, filename={}, kind={}", filename, kind, e);
            return ExtractResult.fail(format, "文件内容提取失败: " + e.getMessage());
        }
    }

    /** MIME 优先，扩展名兜底（浏览器常把 docx 报成 octet-stream） */
    public DocKind resolveKind(String filename, String mimeType) {
        String mime = Objects.isNull(mimeType) ? "" : mimeType.toLowerCase(Locale.ROOT).trim();
        String lower = Objects.isNull(filename) ? "" : filename.toLowerCase(Locale.ROOT);

        if (lower.endsWith(".doc") && !lower.endsWith(".docx")) return DocKind.LEGACY_OLE;
        if (lower.endsWith(".ppt") && !lower.endsWith(".pptx")) return DocKind.LEGACY_OLE;
        if (lower.endsWith(".xls") && !lower.endsWith(".xlsx")) return DocKind.LEGACY_OLE;

        if ("application/pdf".equals(mime) || lower.endsWith(".pdf")) return DocKind.PDF;
        if (mime.contains("wordprocessingml") || lower.endsWith(".docx")) return DocKind.DOCX;
        if (mime.contains("presentationml") || lower.endsWith(".pptx")) return DocKind.PPTX;
        if (mime.contains("spreadsheetml") || lower.endsWith(".xlsx")) return DocKind.XLSX;

        if (isTextType(mime) || isTextByExtension(lower)) return DocKind.TEXT;
        if (StringUtils.isBlank(mime) || "application/octet-stream".equals(mime)) {
            // 未知 MIME：仅信任明确文本扩展名，其余 unsupported
            if (isTextByExtension(lower)) return DocKind.TEXT;
        }
        return DocKind.UNSUPPORTED;
    }

    public boolean isExtractable(String filename, String mimeType) {
        DocKind k = resolveKind(filename, mimeType);
        return k != DocKind.UNSUPPORTED && k != DocKind.LEGACY_OLE;
    }

    private String decodeText(byte[] data) {
        String utf8 = new String(data, StandardCharsets.UTF_8);
        if (!utf8.contains("\ufffd")) return utf8;
        try {
            String gbk = new String(data, Charset.forName("GBK"));
            if (!gbk.contains("\ufffd")) return gbk;
        } catch (Exception ignored) {}
        return utf8;
    }

    private boolean isTextType(String mimeType) {
        if (StringUtils.isBlank(mimeType)) return false;
        if (TEXT_MIME_TYPES.contains(mimeType)) return true;
        return mimeType.startsWith("text/");
    }

    private boolean isTextByExtension(String lowerFilename) {
        if (Objects.isNull(lowerFilename)) return false;
        String[] textExts = {".txt", ".csv", ".json", ".md", ".markdown", ".xml", ".html", ".htm",
                ".css", ".js", ".ts", ".py", ".java", ".sql", ".sh", ".bat",
                ".yml", ".yaml", ".toml", ".ini", ".cfg", ".conf", ".log",
                ".c", ".cpp", ".h", ".hpp", ".go", ".rs", ".rb", ".php"};
        for (String ext : textExts) {
            if (lowerFilename.endsWith(ext)) return true;
        }
        return false;
    }

    private String extractPdf(byte[] data) throws IOException {
        try (PDDocument doc = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc).trim();
        }
    }

    private String extractDocx(byte[] data) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph para : doc.getParagraphs()) {
                String text = para.getText();
                if (StringUtils.isNotBlank(text)) sb.append(text).append('\n');
            }
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        String text = cell.getText();
                        if (StringUtils.isNotBlank(text)) sb.append(text).append('\t');
                    }
                    sb.append('\n');
                }
            }
            return sb.toString().trim();
        }
    }

    private String extractPptx(byte[] data) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(data))) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (XSLFSlide slide : ppt.getSlides()) {
                i++;
                sb.append("--- Slide ").append(i).append(" ---\n");
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (StringUtils.isNotBlank(text)) sb.append(text).append('\n');
                    }
                }
                sb.append('\n');
            }
            return sb.toString().trim();
        }
    }

    private String extractXlsx(byte[] data) throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                XSSFSheet sheet = workbook.getSheetAt(i);
                sb.append("--- ").append(sheet.getSheetName()).append(" ---\n");
                for (int rowIdx = sheet.getFirstRowNum(); rowIdx <= sheet.getLastRowNum(); rowIdx++) {
                    XSSFRow row = sheet.getRow(rowIdx);
                    if (Objects.isNull(row)) continue;
                    StringBuilder rowStr = new StringBuilder();
                    short first = row.getFirstCellNum();
                    if (first < 0) continue;
                    for (int cellIdx = first; cellIdx < row.getLastCellNum(); cellIdx++) {
                        if (cellIdx > first) rowStr.append('\t');
                        XSSFCell cell = row.getCell(cellIdx);
                        if (Objects.nonNull(cell)) rowStr.append(cell.toString());
                    }
                    if (!rowStr.isEmpty()) sb.append(rowStr).append('\n');
                }
                sb.append('\n');
            }
            return sb.toString().trim();
        }
    }
}
