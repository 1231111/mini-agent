package com.miniagent.agent.todo;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import com.miniagent.agent.tool.WorkspaceContext;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TodoSemanticDocumentTest {
    @TempDir
    Path dir;

    @BeforeEach void bindWorkspace() { WorkspaceContext.setRootOverride(dir); }
    @AfterEach void clearWorkspace() { WorkspaceContext.clearRootOverride(); }

    @Test
    void rejectsPlainTextMasqueradingAsOfficeOrPdf() throws Exception {
        for (String extension : List.of("docx", "xlsx", "pptx", "pdf", "doc", "xls", "ppt")) {
            Path path = dir.resolve("fake." + extension);
            Files.writeString(path, "# Report\n\nThis is plain text and is not a valid binary document.");
            assertFalse(validate(path).ok(), extension);
        }
    }

    @Test
    void acceptsDocumentsWrittenByTheirFormatLibraries() throws Exception {
        Path docx = dir.resolve("report.docx");
        try (var document = new XWPFDocument(); var out = Files.newOutputStream(docx)) {
            document.createParagraph().createRun().setText("Report content");
            document.write(out);
        }
        Path xlsx = dir.resolve("report.xlsx");
        try (var workbook = new XSSFWorkbook(); var out = Files.newOutputStream(xlsx)) {
            workbook.createSheet("Data").createRow(0).createCell(0).setCellValue("Value");
            workbook.write(out);
        }
        Path pptx = dir.resolve("report.pptx");
        try (var slides = new XMLSlideShow(); var out = Files.newOutputStream(pptx)) {
            slides.createSlide();
            slides.write(out);
        }
        Path pdf = dir.resolve("report.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }
        for (Path path : List.of(docx, xlsx, pptx, pdf)) {
            TodoSemanticValidator.Result result = validate(path);
            assertTrue(result.ok(), () -> path + ": " + result.error());
            assertFalse(result.contentHash().isBlank());
        }
    }

    private static TodoSemanticValidator.Result validate(Path path) {
        return TodoSemanticValidator.validate("交付文档", "file_exists:" + path, path.toString());
    }
}
