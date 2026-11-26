package com.miniagent.agent.tool;

import com.miniagent.common.io.AtomicFiles;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipInputStream;

/** Shared, request-local document budgets. Never modifies POI's process-global ZIP security settings. */
final class DocumentResourceBudget {
    static final int MAX_INPUT_BYTES = 8 * 1024 * 1024;
    static final int MAX_OUTPUT_BYTES = 8 * 1024 * 1024;
    static final int MAX_CONTENT_CHARS = 1_000_000;
    static final int MAX_BLOCKS = 10_000;
    static final int MAX_RUNS = 50_000;
    static final int MAX_XML_ELEMENTS = 100_000;
    static final int MAX_ZIP_ENTRIES = 512;
    static final int MAX_ENTRY_BYTES = 4 * 1024 * 1024;
    static final int MAX_EXPANDED_BYTES = 16 * 1024 * 1024;
    static final int MAX_XML_DEPTH = 128;
    static final int MAX_SPREADSHEET_ROWS = 10_000;
    static final int MAX_SPREADSHEET_COLUMNS = 256;
    static final int MAX_SPREADSHEET_CELLS = 50_000;
    static final int MAX_CELL_CHARS = 32_767;
    static final int MAX_SLIDES = 200;
    static final int MAX_SLIDE_ITEMS = 1_000;

    private DocumentResourceBudget() { }

    static void requireText(String value, String field) {
        requireActive();
        if (value != null && value.length() > MAX_CONTENT_CHARS)
            throw new IllegalArgumentException(field + " 超过文档输入上限 " + MAX_CONTENT_CHARS + " 字符，请拆分材料");
    }

    static void requireActive() {
        if (Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("文档操作已取消");
    }

    static byte[] readStableFile(Path path) throws IOException {
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink()) throw new IOException("文档编辑仅支持普通文件，不跟随符号链接");
        if (before.size() > MAX_INPUT_BYTES) throw new IOException("文档文件超过输入上限 " + MAX_INPUT_BYTES + " 字节");
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_INPUT_BYTES + 1); }
        if (bytes.length > MAX_INPUT_BYTES) throw new IOException("文档在读取期间超过输入字节上限");
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || after.isSymbolicLink() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime()) || !Objects.equals(before.fileKey(), after.fileKey()))
            throw new IOException("文档在读取期间发生变化，请重新读取后重试");
        return bytes;
    }

    static String decodeText(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    static void requireUnchanged(Path path, byte[] original) throws IOException {
        if (!java.security.MessageDigest.isEqual(original, readStableFile(path)))
            throw new IOException("文档在编辑期间被外部修改，已拒绝覆盖");
    }

    static void validateDocx(byte[] source) throws IOException {
        Set<String> names = new HashSet<>();
        long expanded = 0;
        XmlBudget handler = new XmlBudget();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(source))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("文档解压已取消");
                if (names.size() >= MAX_ZIP_ENTRIES) throw new IOException("Office ZIP 条目数超过上限 " + MAX_ZIP_ENTRIES);
                String name = entry.getName();
                if (!names.add(name)) throw new IOException("Office ZIP 存在重复条目: " + name);
                if (name.startsWith("/") || name.contains("\\") || java.util.Arrays.asList(name.split("/")).contains(".."))
                    throw new IOException("Office ZIP 条目路径无效");
                if (entry.isDirectory()) continue;
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = zip.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("文档解压已取消");
                    if (data.size() + n > MAX_ENTRY_BYTES) throw new IOException("Office ZIP 单条目解压超过上限 " + MAX_ENTRY_BYTES + " 字节");
                    expanded += n;
                    if (expanded > MAX_EXPANDED_BYTES) throw new IOException("Office ZIP 总解压量超过上限 " + MAX_EXPANDED_BYTES + " 字节");
                    data.write(buffer, 0, n);
                }
                if (entry.getCompressedSize() > 0 && data.size() > 100_000
                        && data.size() > entry.getCompressedSize() * 200L)
                    throw new IOException("Office ZIP 压缩比异常，拒绝疑似 ZIP bomb");
                if (name.endsWith(".xml") || name.endsWith(".rels")) validateXml(data.toByteArray(), handler);
            }
        }
        if (!names.contains("[Content_Types].xml") || !names.contains("word/document.xml"))
            throw new IOException("不是有效的 DOCX 包：缺少必需条目");
    }

    private static void validateXml(byte[] data, XmlBudget handler) throws IOException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var parser = factory.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            parser.parse(new InputSource(new ByteArrayInputStream(data)), handler);
        } catch (Exception invalid) {
            throw new IOException("Office XML 无效或超过安全预算: " + invalid.getMessage(), invalid);
        }
    }

    private static final class XmlBudget extends DefaultHandler {
        private int elements;
        private int depth;
        @Override public void startDocument() { depth = 0; }
        @Override public void startElement(String uri, String local, String name, org.xml.sax.Attributes attrs) throws SAXException {
            if (++elements > MAX_XML_ELEMENTS) throw new SAXException("XML 元素数超过上限 " + MAX_XML_ELEMENTS);
            if (++depth > MAX_XML_DEPTH) throw new SAXException("XML 嵌套深度超过上限 " + MAX_XML_DEPTH);
        }
        @Override public void endElement(String uri, String local, String name) { depth--; }
        @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
    }

    static void write(Path target, AtomicFiles.ContentWriter writer) throws IOException {
        AtomicFiles.write(target, out -> writer.writeTo(new BoundedOutput(out)));
    }

    /** Serialize first, then check the source again immediately before committing the temporary file. */
    static void writeUnchanged(Path target, byte[] original, AtomicFiles.ContentWriter writer) throws IOException {
        AtomicFiles.write(target, out -> {
            writer.writeTo(new BoundedOutput(out));
            requireUnchanged(target, original);
        });
    }

    private static final class BoundedOutput extends FilterOutputStream {
        private long written;
        private BoundedOutput(OutputStream output) { super(output); }
        @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
        @Override public void write(byte[] data, int start, int length) throws IOException { reserve(length); out.write(data, start, length); }
        private void reserve(int count) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new IOException("文档写入已取消");
            if (written + count > MAX_OUTPUT_BYTES) throw new IOException("文档输出超过上限 " + MAX_OUTPUT_BYTES + " 字节，原文件保持不变");
            written += count;
        }
    }
}
