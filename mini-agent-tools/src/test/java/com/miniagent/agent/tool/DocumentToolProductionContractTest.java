package com.miniagent.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniagent.agent.tool.impl.*;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentToolProductionContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    @AfterEach void clearContext() { WorkspaceContext.clearAll(); }

    @Test
    void wordContainsDefinedHeadingsRealListsSingleParagraphPropertiesAndPageField() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        WriteDocxParams params = new WriteDocxParams();
        params.setPath("nested/report.docx");
        params.setContent("# Heading\nbody\n- bullet\n1. first\n3. third");
        params.setAddHeaderFooter(true);
        assertSuccess(new WriteDocxTool().handle(params));
        Path path = workspace.resolve("nested/report.docx");
        try (var in = Files.newInputStream(path); var doc = new XWPFDocument(in)) {
            assertThat(doc.getStyles().styleExist("Heading1")).isTrue();
            assertThat(doc.getParagraphs()).hasSize(5);
            assertThat(doc.getParagraphs().subList(2, 5)).allSatisfy(p -> {
                assertThat(p.getNumID()).isNotNull();
                assertThat(doc.getNumbering().getNum(p.getNumID())).isNotNull();
            });
            for (var paragraph : doc.getParagraphs()) {
                var props = paragraph.getCTP().getDomNode().getChildNodes();
                int propertyCount = 0;
                for (int i = 0; i < props.getLength(); i++) if ("pPr".equals(props.item(i).getLocalName())) propertyCount++;
                assertThat(propertyCount).isLessThanOrEqualTo(1);
            }
            assertThat(doc.getFooterList().get(0).getParagraphs().stream()
                    .map(p -> p.getCTP().xmlText()).toList()).anySatisfy(xml -> assertThat(xml).contains("PAGE"));
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            assertThat(zip.getEntry("word/styles.xml")).isNotNull();
            assertThat(zip.getEntry("word/numbering.xml")).isNotNull();
        }
    }

    @Test
    void htmlConversionPreservesOrderDecodesEntitiesAndInlineEmphasisWithoutTagResidue() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        HtmlToDocxParams params = new HtmlToDocxParams();
        params.setPath("html.docx");
        params.setHtmlContent("<html><head><title>hidden</title></head><body><h1>Heading</h1><p>before &amp; <strong>bold</strong></p>"
                + "<ul><li>one</li><li>two</li></ul><p>after</p></body></html>");
        assertSuccess(new HtmlToDocxTool().handle(params));
        try (var in = Files.newInputStream(workspace.resolve("html.docx")); var doc = new XWPFDocument(in)) {
            assertThat(doc.getParagraphs().stream().map(p -> p.getText()).filter(s -> !s.isBlank()).toList())
                    .containsExactly("Heading", "before & bold", "one", "two", "after");
            assertThat(doc.getParagraphs().get(1).getRuns()).anySatisfy(run -> {
                assertThat(run.text()).isEqualTo("bold");
                assertThat(run.isBold()).isTrue();
            });
        }
    }

    @Test
    void explicitWordPrependAndDeleteChangeBodyOrderAndDoNotLeaveEmptyParagraphs() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        WriteDocxParams source = new WriteDocxParams();
        source.setPath("edit.docx");
        source.setContent("original\nremove this\nlast");
        assertSuccess(new WriteDocxTool().handle(source));
        EditDocumentTool editor = new EditDocumentTool();
        EditDocumentParams change = new EditDocumentParams();
        change.setPath("edit.docx"); change.setInstruction("prepend"); change.setOperation("prepend"); change.setNewContent("first");
        assertSuccess(ReflectionTestUtils.invokeMethod(editor, "handle", change));
        change.setInstruction("delete"); change.setOperation("delete"); change.setNewContent("remove this");
        assertSuccess(ReflectionTestUtils.invokeMethod(editor, "handle", change));
        try (var in = Files.newInputStream(workspace.resolve("edit.docx")); var doc = new XWPFDocument(in)) {
            assertThat(doc.getParagraphs().stream().map(p -> p.getText()).toList()).containsExactly("first", "original", "last");
        }
    }

    @Test
    void unifiedWordWriterAppliesStyleAndDoesNotTreatLessThanAsHtml() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        WriteDocumentTool tool = unifiedTool();
        WriteDocumentParams params = new WriteDocumentParams();
        params.setPath("formula.docx"); params.setContent("a < b"); params.setFontFamily("Arial"); params.setFontSize(17.0);
        assertSuccess(ReflectionTestUtils.invokeMethod(tool, "handle", params));
        try (var in = Files.newInputStream(workspace.resolve("formula.docx")); var doc = new XWPFDocument(in)) {
            var run = doc.getParagraphs().get(0).getRuns().get(0);
            assertThat(run.text()).isEqualTo("a < b");
            assertThat(run.getFontFamily()).isEqualTo("Arial");
            assertThat(run.getFontSizeAsDouble()).isEqualTo(17.0);
        }
    }

    @Test
    void spreadsheetPreservesLongIdentifiersAndReusesHeaderStyle() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        WriteXlsxParams params = new WriteXlsxParams();
        params.setPath("data.xlsx"); params.setHeaders("[\"id\",\"amount\",\"label\"]");
        params.setData("[[9223372036854775807,12.5,\"ok\"]]");
        assertSuccess(new WriteXlsxTool().handle(params));
        try (var in = Files.newInputStream(workspace.resolve("data.xlsx")); var workbook = new XSSFWorkbook(in)) {
            var sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("9223372036854775807");
            assertThat(sheet.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(12.5);
            assertThat(workbook.getNumCellStyles()).isEqualTo(2);
        }
    }

    @Test
    void slidesUsePointDimensionsAndPersistSpeakerNotes() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        WritePptxParams params = new WritePptxParams();
        params.setPath("slides.pptx"); params.setSize("standard");
        params.setSlides("[{\"title\":\"Title\",\"content\":\"Body\",\"notes\":\"speaker note\"}]");
        assertSuccess(new WritePptxTool().handle(params));
        try (var in = Files.newInputStream(workspace.resolve("slides.pptx")); var slides = new XMLSlideShow(in)) {
            assertThat(slides.getPageSize().getWidth()).isEqualTo(720);
            assertThat(slides.getPageSize().getHeight()).isEqualTo(540);
            var slide = slides.getSlides().get(0);
            assertThat(slide.getShapes()).allSatisfy(shape -> assertThat(shape.getAnchor().getMaxX()).isLessThanOrEqualTo(720));
            assertThat(slide.getNotes().getTextParagraphs().stream().flatMap(List::stream).map(p -> p.getText()).toList())
                    .contains("speaker note");
        }
    }

    @Test
    void wordReplacementSpansOrdinaryRunsAndNestedTableParagraphsWithoutLosingFormatting() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Path target = workspace.resolve("runs.docx");
        try (var doc = new XWPFDocument()) {
            var paragraph = doc.createParagraph();
            var first = paragraph.createRun(); first.setBold(true); first.setText("prefix tar");
            var second = paragraph.createRun(); second.setItalic(true); second.setText("get suffix target");
            var cell = doc.createTable(1, 1).getRow(0).getCell(0);
            cell.getParagraphs().get(0).createRun().setText("table target");
            cell.getCTTc().addNewTbl().addNewTr().addNewTc().addNewP().addNewR().addNewT().setStringValue("nested target");
            cell.getCTTc().addNewP();
            try (var out = Files.newOutputStream(target)) { doc.write(out); }
        }
        assertSuccess(edit("runs.docx", "replace", "target", "VALUE", null));
        try (var in = Files.newInputStream(target); var doc = new XWPFDocument(in)) {
            var paragraph = doc.getParagraphs().get(0);
            assertThat(paragraph.getText()).isEqualTo("prefix VALUE suffix VALUE");
            assertThat(paragraph.getRuns().get(0).isBold()).isTrue();
            assertThat(paragraph.getRuns().get(1).isItalic()).isTrue();
            var cell = doc.getTables().get(0).getRow(0).getCell(0);
            assertThat(cell.getParagraphs().get(0).getText()).isEqualTo("table VALUE");
            assertThat(cell.getTables().get(0).getRow(0).getCell(0).getText()).isEqualTo("nested VALUE");
        }
    }

    @Test
    void complexWordReplacementIsExplicitFailureAndDoesNotCommitEarlierOrdinaryEdits() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Path target = workspace.resolve("complex.docx");
        try (var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("target ordinary");
            var complex = doc.createParagraph();
            complex.createRun().setText("target complex");
            complex.getCTP().addNewFldSimple().setInstr(" PAGE ");
            try (var out = Files.newOutputStream(target)) { doc.write(out); }
        }
        byte[] before = Files.readAllBytes(target);
        String result = edit("complex.docx", "replace", "target", "VALUE", null);
        assertThat(JSON.readTree(result).path("success").asBoolean()).isFalse();
        assertThat(JSON.readTree(result).path("error").asText()).contains("域");
        assertThat(Files.readAllBytes(target)).isEqualTo(before);
    }

    @Test
    void missingMatchesUnknownOperationsAndUnsupportedInsertionNeverRewriteTheSource() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Path target = workspace.resolve("unchanged.txt");
        Files.writeString(target, "original 世界😀\n");
        byte[] before = Files.readAllBytes(target);
        for (String result : List.of(
                edit("unchanged.txt", "replace", "absent", "replacement", null),
                edit("unchanged.txt", "delete", null, null, "absent"),
                edit("unchanged.txt", "unknown", null, null, "new content"),
                edit("unchanged.txt", "insert_after", null, null, "new content"))) {
            assertThat(JSON.readTree(result).path("success").asBoolean()).as(result).isFalse();
            assertThat(Files.readAllBytes(target)).isEqualTo(before);
        }
    }

    @Test
    void wordDeleteVisitsTableCellsAndPreservesTheMandatoryFinalCellParagraph() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Path target = workspace.resolve("table.docx");
        try (var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("keep body");
            var table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).getParagraphs().get(0).createRun().setText("delete table content");
            table.getRow(0).getCell(1).getParagraphs().get(0).createRun().setText("keep other cell");
            try (var out = Files.newOutputStream(target)) { doc.write(out); }
        }
        assertSuccess(edit("table.docx", "delete", null, null, "delete table content"));
        try (var in = Files.newInputStream(target); var doc = new XWPFDocument(in)) {
            assertThat(doc.getParagraphs().get(0).getText()).isEqualTo("keep body");
            var row = doc.getTables().get(0).getRow(0);
            assertThat(row.getCell(0).getText()).isEmpty();
            assertThat(row.getCell(0).getParagraphs()).hasSize(1);
            assertThat(row.getCell(1).getText()).isEqualTo("keep other cell");
        }
    }

    @Test
    void overBudgetAndMalformedInputsFailBeforeEditingAndKeepTheSourceBytes() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Path large = workspace.resolve("large.txt");
        try (var file = new java.io.RandomAccessFile(large.toFile(), "rw")) { file.setLength(DocumentResourceBudget.MAX_INPUT_BYTES + 1L); }
        String largeResult = edit("large.txt", "append", null, null, "tail");
        assertThat(JSON.readTree(largeResult).path("success").asBoolean()).isFalse();
        assertThat(Files.size(large)).isEqualTo(DocumentResourceBudget.MAX_INPUT_BYTES + 1L);
        Path malformed = workspace.resolve("malformed.txt");
        byte[] invalid = {(byte)0xc3, (byte)0x28};
        Files.write(malformed, invalid);
        assertThat(JSON.readTree(edit("malformed.txt", "append", null, null, "tail")).path("success").asBoolean()).isFalse();
        assertThat(Files.readAllBytes(malformed)).isEqualTo(invalid);
    }

    @Test
    void zipBombAndDeepXmlAreRejectedBeforePoiAndLeaveTheDocumentUnchanged() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        byte[] zipBomb = officeZip("x".repeat(DocumentResourceBudget.MAX_ENTRY_BYTES + 1));
        byte[] deepXml = officeZip("<n>".repeat(DocumentResourceBudget.MAX_XML_DEPTH + 1)
                + "x" + "</n>".repeat(DocumentResourceBudget.MAX_XML_DEPTH + 1));
        for (byte[] invalid : List.of(zipBomb, deepXml)) {
            Path target = workspace.resolve("unsafe.docx");
            Files.write(target, invalid);
            assertThat(JSON.readTree(edit("unsafe.docx", "append", null, null, "tail")).path("success").asBoolean()).isFalse();
            assertThat(Files.readAllBytes(target)).isEqualTo(invalid);
        }
    }

    @Test
    void outputBudgetAndSerializationFailureNeverReplaceTheOriginalOrLeaveTemporaryFiles() throws Exception {
        Path target = workspace.resolve("original.txt");
        Files.writeString(target, "keep original");
        assertThatThrownBy(() -> DocumentResourceBudget.write(target,
                out -> out.write(new byte[DocumentResourceBudget.MAX_OUTPUT_BYTES + 1])))
                .isInstanceOf(IOException.class).hasMessageContaining("输出超过上限");
        assertThat(Files.readString(target)).isEqualTo("keep original");
        assertThatThrownBy(() -> DocumentResourceBudget.write(target, out -> {
            out.write("partial".getBytes(StandardCharsets.UTF_8)); throw new IOException("simulated serialization failure");
        })).isInstanceOf(IOException.class).hasMessageContaining("simulated serialization failure");
        assertThat(Files.readString(target)).isEqualTo("keep original");
        try (var files = Files.list(workspace)) { assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("original.txt"); }
    }

    @Test
    void sourceChangedDuringSerializationIsDetectedImmediatelyBeforeCommit() throws Exception {
        Path target = workspace.resolve("concurrent.txt");
        Files.writeString(target, "original");
        byte[] before = Files.readAllBytes(target);
        assertThatThrownBy(() -> DocumentResourceBudget.writeUnchanged(target, before, out -> {
            out.write("new serialized output".getBytes(StandardCharsets.UTF_8));
            Files.writeString(target, "external edit");
        })).isInstanceOf(IOException.class).hasMessageContaining("外部修改");
        assertThat(Files.readString(target)).isEqualTo("external edit");
        try (var files = Files.list(workspace)) { assertThat(files.map(p -> p.getFileName().toString()).toList()).containsExactly("concurrent.txt"); }
    }

    @Test
    void expandingReplacementAndExcessHtmlBlocksAreRejectedWithoutOverwritingExistingArtifacts() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Path target = workspace.resolve("expanded.txt");
        Files.writeString(target, "a".repeat(100_000));
        byte[] before = Files.readAllBytes(target);
        assertThat(JSON.readTree(edit("expanded.txt", "replace", "a", "b".repeat(20), null)).path("success").asBoolean()).isFalse();
        assertThat(Files.readAllBytes(target)).isEqualTo(before);
        Path htmlTarget = workspace.resolve("existing.docx");
        Files.writeString(htmlTarget, "existing artifact");
        HtmlToDocxParams html = new HtmlToDocxParams(); html.setPath("existing.docx");
        html.setHtmlContent("<p>x</p>".repeat(DocumentResourceBudget.MAX_BLOCKS + 1));
        assertThat(JSON.readTree(new HtmlToDocxTool().handle(html)).path("success").asBoolean()).isFalse();
        assertThat(Files.readString(htmlTarget)).isEqualTo("existing artifact");
    }

    @Test
    void aMissingExplicitEditRejectsTheEntireWordOrTextTransaction() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Path word = workspace.resolve("transaction.docx");
        try (var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("body target");
            try (var out = Files.newOutputStream(word)) { doc.write(out); }
        }
        Path text = workspace.resolve("transaction.txt");
        Files.writeString(text, "original title\nbody target\n");
        for (Path target : List.of(word, text)) {
            byte[] original = Files.readAllBytes(target);
            for (String operation : List.of("replace", "delete", "append")) {
                EditDocumentParams change = new EditDocumentParams();
                change.setPath(target.getFileName().toString()); change.setInstruction(operation); change.setOperation(operation);
                change.setNewTitle("new title");
                if ("replace".equals(operation)) { change.setFindText("missing"); change.setReplaceText("new text"); }
                if ("delete".equals(operation)) change.setNewContent("missing");
                if ("append".equals(operation)) { change.setFindText("missing"); change.setReplaceText("new text"); change.setNewContent("tail"); }
                String result = ReflectionTestUtils.invokeMethod(new EditDocumentTool(), "handle", change);
                assertThat(JSON.readTree(result).path("success").asBoolean()).as(result).isFalse();
                assertThat(Files.readAllBytes(target)).isEqualTo(original);
            }
        }
    }

    @Test
    void successfulTextMultiEditAppliesTitleExactReplacementAndAppendTogether() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Files.writeString(workspace.resolve("multiple.txt"), "old title\nbody target\n");
        EditDocumentParams change = new EditDocumentParams();
        change.setPath("multiple.txt"); change.setInstruction("explicit transaction"); change.setOperation("append");
        change.setNewTitle("new title"); change.setFindText("target"); change.setReplaceText("VALUE"); change.setNewContent("tail");
        assertSuccess(ReflectionTestUtils.invokeMethod(new EditDocumentTool(), "handle", change));
        assertThat(Files.readString(workspace.resolve("multiple.txt"))).isEqualTo("new title\nbody VALUE\n\ntail");
    }

    @Test
    void spreadsheetRejectsRowsColumnsCellsAndLongCellContentBeforeReplacingExistingFile() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Path target = workspace.resolve("bounded.xlsx");
        Files.writeString(target, "existing workbook");
        List<String> invalidData = List.of(
                JSON.writeValueAsString(Collections.nCopies(DocumentResourceBudget.MAX_SPREADSHEET_ROWS + 1, List.of())),
                JSON.writeValueAsString(List.of(Collections.nCopies(DocumentResourceBudget.MAX_SPREADSHEET_COLUMNS + 1, "x"))),
                JSON.writeValueAsString(Collections.nCopies(200, Collections.nCopies(251, "x"))),
                JSON.writeValueAsString(List.of(List.of("x".repeat(DocumentResourceBudget.MAX_CELL_CHARS + 1)))),
                "[[{\"nested\":\"object\"}]]", "[null]", "null");
        for (String invalid : invalidData) {
            WriteXlsxParams params = new WriteXlsxParams(); params.setPath("bounded.xlsx"); params.setData(invalid);
            String result = new WriteXlsxTool().handle(params);
            assertThat(JSON.readTree(result).path("success").asBoolean()).as(result).isFalse();
            assertThat(Files.readString(target)).isEqualTo("existing workbook");
        }
    }

    @Test
    void presentationRejectsSlideCountListSizeUnsupportedContentAndTextBudgetBeforeReplacingExistingFile() throws Exception {
        WorkspaceContext.setRootOverride(workspace);
        Path target = workspace.resolve("bounded.pptx");
        Files.writeString(target, "existing slides");
        List<String> invalidSlides = List.of(
                JSON.writeValueAsString(Collections.nCopies(DocumentResourceBudget.MAX_SLIDES + 1, Map.of("title", "x"))),
                JSON.writeValueAsString(List.of(Map.of("content", Collections.nCopies(DocumentResourceBudget.MAX_SLIDE_ITEMS + 1, "x")))),
                JSON.writeValueAsString(List.of(Map.of("content", "x\n".repeat(DocumentResourceBudget.MAX_BLOCKS + 1)))),
                "[{\"content\":{\"nested\":\"object\"}}]", "[{\"content\":[null]}]", "[null]", "null");
        for (String invalid : invalidSlides) {
            WritePptxParams params = new WritePptxParams(); params.setPath("bounded.pptx"); params.setSlides(invalid);
            String result = new WritePptxTool().handle(params);
            assertThat(JSON.readTree(result).path("success").asBoolean()).as(result).isFalse();
            assertThat(Files.readString(target)).isEqualTo("existing slides");
        }
    }

    private String edit(String path, String operation, String find, String replacement, String content) {
        EditDocumentParams change = new EditDocumentParams();
        change.setPath(path); change.setInstruction(operation); change.setOperation(operation);
        change.setFindText(find); change.setReplaceText(replacement); change.setNewContent(content);
        return ReflectionTestUtils.invokeMethod(new EditDocumentTool(), "handle", change);
    }

    private static byte[] officeZip(String documentXml) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static WriteDocumentTool unifiedTool() {
        WriteDocumentTool tool = new WriteDocumentTool();
        ReflectionTestUtils.setField(tool, "writeDocxTool", new WriteDocxTool());
        ReflectionTestUtils.setField(tool, "htmlToDocxTool", new HtmlToDocxTool());
        return tool;
    }

    private static void assertSuccess(String result) throws Exception { assertThat(JSON.readTree(result).path("success").asBoolean()).as(result).isTrue(); }
}
